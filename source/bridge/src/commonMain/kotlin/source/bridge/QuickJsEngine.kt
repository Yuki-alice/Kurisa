package source.bridge

import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import core.model.toUtf8Bytes
import data.network.CookieStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import source.contract.ContentKind
import source.hostapi.HostApi

/** 源桥异常（JS 侧错误原文保留在 message，便于源作者排查）。 */
class SourceBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 宿主缺 native 库（androidHostTest 的 JVM 起 Android 构件、无可加载 .so；
 * M1 同坑沿用 sentinel 思路，此处显式类型化）。
 * 单测捕获后直接 return（桌面/iOS 有原生库，全量不断言打折）。
 */
class HostMissingException(message: String) : Exception(message)

/**
 * quickjs-kt 引擎（架构 4.6：单源串行 dispatcher + 双层桥）。
 *
 * 单发模型：quickjs-kt 的实例构造器私有，`quickJs {}` 块结束即 close，
 * 因此每次调用都新建实例（polyfill + 脚本 + 实例化 + 调用片段一次求值）。
 * 跨调用状态必须经 loadData/saveData 落宿主 KV（Venera 源本就如此编写，
 * 宿主随时可能重建上下文——这是被迫的优点，不是缺陷）。
 *
 * - 同步桥（`__host.kv/html/convert/cookie/log`）：本地白名单方法，
 *   与 [callSync] 共用同一套 handler（JS 内调与 Kotlin 直调同语义）。
 * - 异步桥（`__host.request` 兼容 + `__hostCall`）：网络走宿主协程。
 * - 超时三件套：`evaluationTimeoutMillis` + `withTimeout` +
 *   `interruptEvaluation()`；块结束自动 `close()`。
 */
class QuickJsEngine(
    private val sourceKey: String,
    private val host: HostApi,
    private val cookies: CookieStore,
    private val dispatchers: SourceDispatchers,
    private val memoryLimitMb: Long = 32,
    private val evalTimeoutMs: Long = 10_000,
    private val appVersion: String = "0.0.0",
    private val locale: String = "zh_CN",
    private val platform: String = "common",
    private val nowMs: () -> Long = { 0L },
) : SourceEngine, SourceCalls {

    private var loadedScript: String? = null
    private var className: String? = null
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun dispatcher() = dispatchers.dispatcherFor(sourceKey)

    override suspend fun load(script: String) {
        val name = findClass(script)
            ?: throw IllegalArgumentException("未找到 `class X extends ComicSource`")
        runCall(
            name, script,
            "if (typeof __source.init === 'function') { await __source.init(); } 'ok';",
        )
        loadedScript = script
        className = name
    }

    override fun callSync(method: String, payload: String): String = when (method) {
        "kv" -> handleKv(payload)
        "html" -> handleHtml(payload)
        "convert" -> handleConvert(payload)
        "cookie" -> handleCookie(payload)
        else -> throw SourceBridgeException("未知同步方法：$method")
    }

    override suspend fun callAsync(method: String, payload: String, timeoutMs: Long): String =
        when (method) {
            "request" -> handleRequest(payload)
            "hostCall" -> handleHostCall(payload)
            else -> throw SourceBridgeException("未知异步方法：$method")
        }

    override fun close() {
        loadedScript = null
        className = null
    }

    // ---------- 类型化调用（搜索/详情/章节；返回源原始 JSON，归一化在 provider 适配层） ----------

    override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int): String {
        val kw = Json.encodeToString(kotlinx.serialization.serializer<String>(), keyword)
        return runCall(
            "await __source.search.load($kw, $optionsJson, $page)" +
                ".then(function (r) { return JSON.stringify(r); })"
        )
    }

    /** 读源声明的 optionList 并按 M1 冻结语义生成默认值数组（传 null 会崩，宿主侧生成）。 */
    override suspend fun defaultSearchOptions(): String {
        val raw = runCall(
            "JSON.stringify((__source.search && __source.search.optionList) || [])"
        )
        val groups = runCatching {
            (json.parseToJsonElement(raw) as? JsonArray)?.map { g ->
                (g as? kotlinx.serialization.json.JsonObject)
                    ?.get("options") as? JsonArray
                    ?: JsonArray(emptyList())
            } ?: emptyList()
        }.getOrElse { emptyList() }
        val defaults = source.contract.ContractPolicy.defaultOptions(
            groups.map { g -> g.mapNotNull { it.jsonPrimitive.contentOrNull } }
        )
        return buildJsonArray {
            defaults.forEach { v ->
                add(v?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }.toString()
    }

    override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String {
        val id = Json.encodeToString(kotlinx.serialization.serializer<String>(), workId)
        val ns = if (kind == ContentKind.NOVEL) "novel" else "comic"
        return runCall(
            "await __source.$ns.loadInfo($id).then(function (r) { return JSON.stringify(r, " +
                "function (k, v) { return v instanceof Map ? Object.fromEntries(v) : v; }); })"
        )
    }

    override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind): String {
        val w = Json.encodeToString(kotlinx.serialization.serializer<String>(), workId)
        val c = Json.encodeToString(kotlinx.serialization.serializer<String>(), chapterId)
        val call = if (kind == ContentKind.NOVEL) {
            "await __source.novel.loadChapter($w, $c)"
        } else {
            "await __source.comic.loadEp($w, $c)"
        }
        return runCall("$call.then(function (r) { return JSON.stringify(r); })")
    }

    override suspend fun invoke(path: String, argsJson: String): String {
        require(path.matches(Regex("""[A-Za-z_$][\w$.]*"""))) { "非法调用路径：$path" }
        val args = runCatching { json.parseToJsonElement(argsJson) }.getOrNull()
            ?: throw SourceBridgeException("参数须为 JSON 数组")
        require(args is JsonArray) { "参数须为 JSON 数组" }
        return runCall(
            "await __source.$path(...$argsJson).then(function (r) { return JSON.stringify(r); })"
        )
    }

    // ---------- 内部 ----------

    private suspend fun runCall(snippet: String): String {
        val name = className
            ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        val script = loadedScript
            ?: throw IllegalStateException("引擎未加载（先调 load）: $sourceKey")
        return runCall(name, script, snippet)
    }

    private suspend fun runCall(className: String, script: String, snippet: String): String =
        withContext(dispatcher()) {
            try {
                withTimeout(evalTimeoutMs) {
                    quickJs(RejectModuleLoader) {
                        memoryLimit = memoryLimitMb * 1024 * 1024
                        maxStackSize = 256L * 1024
                        evaluationTimeoutMillis = evalTimeoutMs
                        define("__host") {
                            function("kv") { payload: String -> handleKv(payload) }
                            function("html") { payload: String -> handleHtml(payload) }
                            function("convert") { payload: String -> handleConvert(payload) }
                            function("cookie") { payload: String -> handleCookie(payload) }
                            function("log") { msg: String ->
                                host.utils.log(msg)
                                msg.length
                            }
                            asyncFunction("request") { payload: String -> handleRequest(payload) }
                            asyncFunction("hostCall") { payload: String -> handleHostCall(payload) }
                        }
                        evaluate<String>(polyfillOf(appVersion, locale, platform))
                        evaluate<String>(script)
                        evaluate<String>("const __source = new $className();")
                        evaluate<String>(snippet)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw SourceBridgeException("源调用超时（${evalTimeoutMs}ms）：$sourceKey", e)
            } catch (e: CancellationException) {
                // 调用方取消（协程 teardown）永不包装，结构化并发不断。
                throw e
            } catch (t: Throwable) {
                // native 缺失是 Error 分支（UnsatisfiedLinkError 等 JVM 专有类型，
                // commonMain 不可直引），按类名识别；其余 Errors 原样抛出（M1 同式）。
                if (isHostMissing(t)) throw HostMissingException("quickjs native 缺失：${t.message}")
                if (t is Exception) throw asBridgeError(t)
                throw t
            }
        }

    private fun findClass(script: String): String? =
        Regex("""class\s+(\w+)\s+extends\s+ComicSource""")
            .find(script)?.groupValues?.get(1)

    private fun isHostMissing(t: Throwable): Boolean {
        // 只认链接错误类名（M1 实录教训：按 "quickjs" 子串匹配会把所有
        // QuickJsException 都误判为宿主缺失——类名本身就含 quickjs）。
        val names = generateSequence(t) { it.cause }.mapNotNull {
            it::class.simpleName
        }.toSet()
        return names.any {
            it.contains("UnsatisfiedLink") || it.contains("NoClassDefFound") ||
                it.contains("InitializerError")
        }
    }

    private fun asBridgeError(t: Throwable): Exception =
        if (t is SourceBridgeException) t
        else SourceBridgeException("源执行失败 [$sourceKey]：${t.message}", t)

    // ---------- 同步桥 handler（JS 绑定与 callSync 共用） ----------

    private fun handleKv(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return ""
        val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return ""
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "save" -> {
                host.storage.set(sourceKey, key, o["value"]?.jsonPrimitive?.contentOrNull.orEmpty())
                "1"
            }
            "delete" -> {
                host.storage.delete(sourceKey, key)
                "1"
            }
            else -> host.storage.get(sourceKey, key).orEmpty()
        }
    }

    private fun handleHtml(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"error":"BAD_PAYLOAD"}"""
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "parse" -> {
                val html = o["html"]?.jsonPrimitive?.contentOrNull.orEmpty()
                """{"doc":${host.html.parse(html)}}"""
            }
            "select" -> {
                val doc = o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return """{"error":"disposed-or-unknown"}"""
                try {
                    // 压平输出：与 HostHtml 契约键一致（"text"/"html"/"attr:*"），
                    // JS 侧 `els[0]["attr:src"]` 直取，不做二次整形。
                    val els = host.html.select(doc, o["css"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    buildString {
                        append('[')
                        els.forEachIndexed { i, el ->
                            if (i > 0) append(',')
                            append("{")
                            append("\"text\":")
                            append(Json.encodeToString(kotlinx.serialization.serializer<String>(), el["text"].orEmpty()))
                            append(",\"html\":")
                            append(Json.encodeToString(kotlinx.serialization.serializer<String>(), el["html"].orEmpty()))
                            el.entries.filter { it.key.startsWith("attr:") }.forEach { e ->
                                append(',')
                                append(Json.encodeToString(kotlinx.serialization.serializer<String>(), e.key))
                                append(':')
                                append(Json.encodeToString(kotlinx.serialization.serializer<String>(), e.value))
                            }
                            append("}")
                        }
                        append(']')
                    }
                } catch (e: Exception) {
                    // disposed 复用抛 IllegalStateException；Ksoup 选择器解析失败等一律收敛为同一错误面
                    //（P6：JS 侧输入永不抛原生异常穿透，只返回错误 JSON）。
                    """{"error":"disposed-or-unknown"}"""
                }
            }
            "dispose" -> {
                o["doc"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { host.html.dispose(it) }
                """{"ok":true}"""
            }
            else -> """{"error":"unknown-op"}"""
        }
    }

    private fun handleConvert(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return ""
        val op = o["op"]?.jsonPrimitive?.contentOrNull ?: return ""
        val input = o["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (op) {
            "md5" -> host.convert.md5(input)
            "sha256" -> host.convert.sha256(input)
            "encodeBase64" -> host.convert.encodeBase64(input.toUtf8Bytes())
            "decodeBase64" -> runCatching {
                host.convert.decodeBase64(input).decodeToString()
            }.getOrDefault("")
            else -> ""
        }
    }

    private fun handleCookie(payload: String): String {
        // 同步 Cookie 三件套（内存态，不挂起；与 KtorHostNetwork 共用同一 CookieStore）。
        // JS 置入记 CookieSource.NETWORK（脚本基于网络数据断言，非用户粘贴/测试预置；
        // 配对 UA 为空——cf_clearance 的 UA 配对只走 WebView/手动导入路径，见架构红线）。
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return "[]"
        val url = o["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (o["op"]?.jsonPrimitive?.contentOrNull) {
            "set" -> {
                val host = CookieStore.hostOf(url)
                val list = o["list"] as? JsonArray ?: return "0"
                list.forEach { el ->
                    val c = el as? kotlinx.serialization.json.JsonObject ?: return@forEach
                    cookies.put(
                        core.model.StoredCookie(
                            domain = c["domain"]?.jsonPrimitive?.contentOrNull?.ifEmpty { host } ?: host,
                            name = c["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            value = c["value"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            obtainedAtEpochMs = nowMs(),
                            source = core.model.CookieSource.NETWORK,
                        ),
                    )
                }
                "1"
            }
            "delete" -> {
                cookies.remove(CookieStore.hostOf(url))
                "1"
            }
            else -> {
                val arr = cookies.matching(url, nowMs()).map { c ->
                    """{"name":${
                        Json.encodeToString(kotlinx.serialization.serializer<String>(), c.name)
                    },"value":${
                        Json.encodeToString(kotlinx.serialization.serializer<String>(), c.value)
                    },"domain":${
                        Json.encodeToString(kotlinx.serialization.serializer<String>(), c.domain)
                    }}"""
                }
                "[${arr.joinToString(",")}]"
            }
        }
    }

    // ---------- 异步桥 handler ----------

    private suspend fun handleRequest(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"status":0,"body":"BAD_PAYLOAD"}"""
        val method = o["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return """{"status":0,"body":"NO_URL"}"""
        val headers = (o["headers"] as? kotlinx.serialization.json.JsonObject)
            ?.entries?.associate { it.key to (it.value.jsonPrimitive.contentOrNull.orEmpty()) }
            .orEmpty()
        return try {
            val r = if (method == "POST") {
                host.network.post(url, headers, o["data"]?.jsonPrimitive?.contentOrNull)
            } else {
                host.network.get(url, headers)
            }
            val body = if (r.body.length > 300_000) r.body.take(300_000) else r.body
            """{"status":${r.status},"body":${
                Json.encodeToString(kotlinx.serialization.serializer<String>(), body)
            }}"""
        } catch (e: Exception) {
            """{"status":0,"body":"HOST_ERROR"}"""
        }
    }

    private suspend fun handleHostCall(payload: String): String {
        val o = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return """{"error":"BAD_PAYLOAD"}"""
        return when (o["method"]?.jsonPrimitive?.contentOrNull) {
            "network.get", "network.post" -> handleRequest(payload)
            else -> """{"error":"unsupported-hostcall"}"""
        }
    }

    companion object {
        /**
         * 宿主 polyfill（M1 冻结形状 + Cookie 同步三件套 + Convert 最小全局）。
         * 直接插值（String.format 是 JVM 专有，commonMain 不可用）。
         * KV 命名空间在 Kotlin 侧以前缀强制（脚本传参不可信），此处不占位。
         */
        private fun polyfillOf(appVersion: String, locale: String, platform: String): String = """
            const APP = { locale: '$locale', version: '$appVersion', platform: '$platform' };
            class ComicSource {
              loadData(key) {
                return __host.kv(JSON.stringify({ op: 'load', key: key })) || null;
              }
              saveData(key, value) {
                __host.kv(JSON.stringify({ op: 'save', key: key, value: String(value) }));
              }
              deleteData(key) {
                __host.kv(JSON.stringify({ op: 'delete', key: key }));
              }
            }
            class Comic { constructor(o) { Object.assign(this, o || {}); } }
            class ComicDetails { constructor(o) { Object.assign(this, o || {}); } }
            const Network = {
              get: async function (url, headers) {
                const r = await __host.request(JSON.stringify({ method: 'GET', url: url }));
                return JSON.parse(r);
              },
              post: async function (url, headers, data) {
                const r = await __host.request(JSON.stringify({ method: 'POST', url: url }));
                return JSON.parse(r);
              },
              getCookies: function (url) {
                return JSON.parse(__host.cookie(JSON.stringify({ op: 'get', url: url })));
              },
              setCookies: function (url, list) {
                return __host.cookie(JSON.stringify({ op: 'set', url: url, list: list })) === '1';
              },
              deleteCookies: function (url) {
                return __host.cookie(JSON.stringify({ op: 'delete', url: url })) === '1';
              }
            };
            const Convert = {
              md5: function (s) { return __host.convert(JSON.stringify({ op: 'md5', value: String(s) })); },
              sha256: function (s) { return __host.convert(JSON.stringify({ op: 'sha256', value: String(s) })); },
              encodeBase64: function (s) { return __host.convert(JSON.stringify({ op: 'encodeBase64', value: String(s) })); },
              decodeBase64: function (s) { return __host.convert(JSON.stringify({ op: 'decodeBase64', value: String(s) })); }
            };
            class HtmlDocument {
              constructor(html) {
                const r = JSON.parse(__host.html(JSON.stringify({ op: 'parse', html: html })));
                this.__doc = r.doc;
              }
              querySelectorAll(css) {
                const r = __host.html(JSON.stringify({ op: 'select', doc: this.__doc, css: css }));
                if (typeof r === 'string' && r.indexOf('disposed') >= 0) throw 'HtmlDocument disposed';
                return JSON.parse(r);
              }
              querySelector(css) {
                const a = this.querySelectorAll(css);
                return a[0] || null;
              }
              dispose() { __host.html(JSON.stringify({ op: 'dispose', doc: this.__doc })); }
            }
            const console = { log: function () { __host.log(Array.prototype.slice.call(arguments).join(' ')); } };
        """.trimIndent()

        /**
         * 模块导入拒绝器（源脚本禁止 import 外部模块：供应链收口，见 C1）。
         */
        private object RejectModuleLoader : com.dokar.quickjs.ModuleLoader {
            override fun load(name: String): com.dokar.quickjs.ModuleContent =
                throw SourceBridgeException("模块导入不支持：$name")
        }
    }
}
