package spike

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 系统 WebView 子进程的 Kotlin 端代理。
 *
 * 设计取舍（对齐 ADR-009 修订）：
 * - **不内嵌浏览器**：调用系统内核（Linux WebKitGTK / Windows WebView2 / macOS WKWebView），
 *   随包体积增加只有几百 KB，而不是 JCEF 的 150–200MB。
 * - **独立进程**：WebView 的主线程/事件循环要求与 AWT 冲突（macOS 尤其硬），
 *   放子进程里可以彻底绕开，还能隔离 WebView 崩溃。
 * - **stdio 而非端口**：Windows 上监听端口会触发防火墙弹窗。
 *
 * 目前只有 Linux 实现（[host_gtk.c]），接口保持平台无关，Windows/macOS 后续按同样协议实现。
 */
class WebviewHost(
    binary: File = locateBinary(),
    dataDir: File? = null,
    profile: String = "default",
    noSandbox: Boolean = System.getenv("KURISA_WEBVIEW_NO_SANDBOX") != null,
) : AutoCloseable {

    private val process: Process
    private val stdin: BufferedWriter
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val events = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
    private val nextId = AtomicInteger(0)

    /** 由 `ready` 事件带回，必须与 Cookie 一起存：cf_clearance 与 UA 绑定。 */
    @Volatile
    var userAgent: String = ""
        private set

    @Volatile
    var engineVersion: String = ""
        private set

    init {
        val pb = ProcessBuilder(binary.absolutePath)
        dataDir?.let {
            it.mkdirs()
            pb.environment()["KURISA_WEBVIEW_DATA"] = it.absolutePath
        }
        pb.environment()["KURISA_WEBVIEW_PROFILE"] = profile
        if (noSandbox) {
            // 容器 / CI 里没有 user namespace，WebKit 的 WebProcess 起不来
            pb.environment()["KURISA_WEBVIEW_NO_SANDBOX"] = "1"
        }
        pb.redirectErrorStream(false)
        process = pb.start()
        stdin = process.outputStream.bufferedWriter()

        thread(isDaemon = true, name = "webview-host-reader") {
            val reader: BufferedReader = process.inputStream.bufferedReader()
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("{")) return@forEachLine // GTK/EGL 噪声
                val obj = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(trimmed).jsonObject
                }.getOrNull() ?: return@forEachLine

                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                if (id != null) {
                    pending.remove(id)?.complete(obj)
                } else {
                    if (obj["ev"]?.jsonPrimitive?.contentOrNull == "ready") {
                        userAgent = obj["ua"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        engineVersion = obj["engine"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    events += obj
                }
            }
        }
        awaitReady()
    }

    private fun awaitReady(timeoutMs: Long = 15_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (userAgent.isNotEmpty()) return
            Thread.sleep(20)
        }
        throw WebviewHostException("webview host 未在 ${timeoutMs}ms 内就绪（可能缺少 WebKitGTK）")
    }

    private fun call(cmd: JsonObject, timeoutMs: Long = 60_000): JsonObject {
        val id = nextId.incrementAndGet()
        val body = JsonObject(cmd.toMutableMap().apply { put("id", JsonPrimitive(id)) })
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        synchronized(stdin) {
            stdin.write(body.toString())
            stdin.newLine()
            stdin.flush()
        }
        val res = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw WebviewHostException("webview host 超时: $cmd")
        }
        res["error"]?.jsonPrimitive?.contentOrNull?.let { throw WebviewHostException(it) }
        return res
    }

    fun open(url: String, width: Int = 1000, height: Int = 800) {
        call(buildJsonObject {
            put("cmd", "open")
            put("url", url)
            put("width", width)
            put("height", height)
        })
    }

    /** 等待页面加载完成；返回 page 的 url 与 title。 */
    fun awaitLoaded(timeoutMs: Long = 60_000): Pair<String, String> {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            events.firstOrNull { it["ev"]?.jsonPrimitive?.contentOrNull == "loaded" }?.let {
                events.remove(it)
                val url = it["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                var title = it["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                // WebKit 的 title 常常晚于 load-finished 一拍，空则回查一次
                if (title.isEmpty()) title = runCatching { eval("document.title") }.getOrDefault("")
                return url to title
            }
            Thread.sleep(50)
        }
        throw WebviewHostException("页面未在 ${timeoutMs}ms 内加载完成")
    }

    /**
     * 读取某 URL 下系统 CookieManager 里的全部 Cookie（含 HttpOnly）。
     *
     * 这是本方案存在的唯一理由：`cf_clearance` / `__cf_bm` 是 HttpOnly，
     * 在页面里 `document.cookie` 永远读不到，只能走原生 CookieManager。
     * 返回的每条都带上产生它的 UA，避免换 UA 重放导致重新挑战。
     */
    fun cookies(url: String): List<StoredCookie> {
        val res = call(buildJsonObject {
            put("cmd", "cookies")
            put("url", url)
        })
        val arr = res["ok"] as? JsonArray ?: return emptyList()
        val now = System.currentTimeMillis()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            StoredCookie(
                domain = o["domain"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = name,
                value = o["value"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                path = o["path"]?.jsonPrimitive?.contentOrNull ?: "/",
                expiresAtEpochMs = o["expiresAt"]?.jsonPrimitive?.long,
                obtainedAtEpochMs = now,
                source = CookieSource.WEBVIEW,
                pairedUserAgent = userAgent,
            )
        }
    }

    fun eval(js: String): String = call(buildJsonObject {
        put("cmd", "eval")
        put("js", js)
    })["ok"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun snapshot(path: File) {
        path.parentFile?.mkdirs()
        call(buildJsonObject {
            put("cmd", "snapshot")
            put("path", path.absolutePath)
        })
    }

    fun closeWindow() {
        call(buildJsonObject { put("cmd", "close") })
    }

    override fun close() {
        runCatching {
            call(buildJsonObject { put("cmd", "quit") }, 5_000)
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        /**
         * 定位 host 可执行文件。
         * 正式发行时应打进应用资源目录；M0 阶段直接从构建输出取。
         */
        fun locateBinary(): File {
            System.getProperty("kurisa.webview.host")?.let { return File(it) }
            val rel = "desktop-webview-host/build/linux/kurisa-webview-host"
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            repeat(5) {
                val candidate = dir?.resolve(rel)
                if (candidate != null && candidate.isFile) return candidate
                dir = dir?.parentFile
            }
            throw WebviewHostException(
                "找不到 kurisa-webview-host，请先执行 desktop-webview-host/build-linux.sh，" +
                    "或用 -Dkurisa.webview.host=<path> 指定",
            )
        }
    }
}

class WebviewHostException(message: String) : Exception(message)
