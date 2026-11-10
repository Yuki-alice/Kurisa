package spike

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress

/**
 * 系统 WebView 方案的验证入口（Linux / WebKitGTK）。
 *
 * 验证链：Kotlin 主进程 → 子进程 → 系统 WebKitGTK → 真实页面 → 原生 CookieManager。
 * 核心断言只有一条：**HttpOnly Cookie（cf_clearance 的形态）JS 读不到，但原生层能读到且带 UA**。
 *
 * 跑法：
 *   desktop-webview-host/build-linux.sh
 *   xvfb-run -a ./gradlew :shared:webviewHostSpike
 *   xvfb-run -a ./gradlew :shared:webviewHostSpike --args="https://mangadex.org/"
 */
fun main(args: Array<String>) {
    var pass = 0
    var fail = 0
    fun check(name: String, ok: Boolean, detail: String = "") {
        if (ok) { pass++; println("  PASS  $name${if (detail.isEmpty()) "" else "  ($detail)"}") }
        else { fail++; println("  FAIL  $name${if (detail.isEmpty()) "" else "  ($detail)"}") }
    }

    val html = """
        <!doctype html><meta charset="utf-8"><title>Kurisa WebView Spike</title>
        <style>body{font:16px system-ui;background:#101014;color:#e8e8ea;padding:40px}</style>
        <h1>system webview works</h1><p id="out">-</p>
        <script>
          document.cookie = "js_visible=hello; path=/";
          document.getElementById('out').textContent = "document.cookie=" + document.cookie;
        </script>
    """.trimIndent()

    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { ex: HttpExchange ->
        val body = html.toByteArray()
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        // 模拟 cf_clearance 的形态：HttpOnly + 不透明长串
        ex.responseHeaders.add("Set-Cookie", "cf_fake=OPAQUE_378_CHARS; Path=/; HttpOnly")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
    server.start()
    val localUrl = "http://127.0.0.1:${server.address.port}/"

    val workDir = File(System.getProperty("user.dir"))
    val outDir = listOf(File(workDir, "build/screenshots"), File(workDir, "../build/screenshots"))
        .firstOrNull { it.parentFile?.exists() == true } ?: File(workDir, "build/screenshots")

    println("========== 系统 WebView（Linux / WebKitGTK）==========")
    WebviewHost().use { host ->
        println("  host ready: engine=${host.engineVersion}")
        println("  ua: ${host.userAgent}")
        check("host 启动", host.userAgent.isNotEmpty())
        check("引擎是系统 WebKitGTK", host.engineVersion.startsWith("2."))

        host.open(localUrl, 900, 620)
        val (url, title) = host.awaitLoaded()
        println("  loaded: $url  title=$title")
        check("加载本地页面", url.startsWith("http://127.0.0.1"))

        val jsCookie = host.eval("document.cookie")
        println("  document.cookie: $jsCookie")
        check("JS 能写普通 cookie", jsCookie.contains("js_visible=hello"))
        check("JS 看不到 HttpOnly cookie", !jsCookie.contains("cf_fake"))

        val cookies = host.cookies(localUrl)
        cookies.forEach {
            println("  cookie: ${it.name}=${it.value} domain=${it.domain} source=${it.source}")
        }
        val fake = cookies.firstOrNull { it.name == "cf_fake" }
        check("原生读到 HttpOnly cookie", fake != null)
        check("Cookie 自动配对 UA", fake?.pairedUserAgent == host.userAgent)
        check("配对 UA 非空", !fake?.pairedUserAgent.isNullOrEmpty())

        // 用真实 CookieStore 走一遍注入逻辑，确认能拼出请求头
        val store = CookieStore()
        store.putAll(cookies)
        val header = store.headerFor(localUrl, System.currentTimeMillis())
        println("  header: $header")
        check("能拼出 Cookie 请求头", header?.contains("cf_fake=") == true)

        val shot = File(outDir, "webview-host-linux.png")
        host.snapshot(shot)
        check("快照落盘", shot.isFile && shot.length() > 5_000, shot.absolutePath)

        if (args.isNotEmpty()) {
            val target = args[0]
            println("  ---- 真实站点: $target")
            host.open(target, 1100, 800)
            runCatching { host.awaitLoaded(90_000) }
                .onSuccess { println("  loaded: ${it.first}  title=${it.second}") }
                .onFailure { println("  !! 加载超时: ${it.message}") }
            // 挑战页的 JS 校验需要时间，等正文出现再截图，否则只能拍到白屏
            var waited = 0
            while (waited < 40) {
                val len = runCatching { host.eval("document.body ? document.body.innerText.length : 0") }
                    .getOrDefault("0").toIntOrNull() ?: 0
                if (len > 200) break
                Thread.sleep(1_000); waited++
            }
            println("  waited ${waited}s for page body")
            println("  title: ${host.eval("document.title")}")
            val realCookies = host.cookies(target)
            println("  cookies: " + realCookies.joinToString { "${it.name}(httpOnly?)" })
            realCookies.forEach {
                println("    ${it.name} len=${it.value.length} domain=${it.domain}")
            }
            check("真实站点可加载", host.eval("document.title").isNotEmpty())
            check("真实站点拿到 HttpOnly cookie", realCookies.isNotEmpty())
            host.snapshot(File(outDir, "webview-host-real-site.png"))
        }
    }
    server.stop(0)

    println("\n==== 结果 ====")
    println("PASS=$pass FAIL=$fail")
    if (fail > 0) kotlin.system.exitProcess(1)
}
