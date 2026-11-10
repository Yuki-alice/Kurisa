package spike

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 桌面端「不做内嵌 WebView」的可行性验证。
 *
 * 待验证命题：
 *   `account.loginWithWebview` 在源契约里的唯一作用是「在真实浏览器里完成登录，
 *   把结果 Cookie 写进 CookieJar」。源判断登录态读的是 `Network.getCookies`，
 *   而不是 WebView 本身。
 *
 * 若命题成立，则宿主只要提供**其它写入 CookieJar 的入口**（用户在系统浏览器登录后
 * 粘贴 Cookie），桌面端就不需要内嵌任何浏览器引擎。
 *
 * 实验设计：同一份源、同样的调用路径，只改变 CookieJar 初值，观察源的行为差异。
 */
fun main(args: Array<String>) = runBlocking {
    val path = args.getOrNull(0) ?: error("用法: accountSpike <source.js>")
    val code = File(path).readText()
    val http = JdkSourceHttp()

    println("================ A. 空 CookieJar ================")
    val a = probe(http, code, emptyMap())
    dump("A", a)

    val acct = a.objOrNull("account")
    val webviewUrl = acct?.str("webviewUrl")
    val fields = runCatching {
        acct?.get("cookieFields")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    }.getOrNull().orEmpty()

    if (webviewUrl == null || webviewUrl.isBlank()) {
        println("\n该源未声明 loginWithWebview，无需做替代方案验证。")
        return@runBlocking
    }

    val host = hostOf(webviewUrl)
    val fieldName = fields.firstOrNull() ?: "spike_session"
    val seed = mapOf(host to mapOf(fieldName to "spike-fake-value"))

    println("\n================ B. 注入 Cookie ================")
    println("注入目标: host=$host  name=$fieldName  value=spike-fake-value")
    val b = probe(http, code, seed)
    dump("B", b)

    // ---- 对照 ----
    println("\n================ 对照结论 ================")
    val aCookies = cookiesOf(a)
    val bCookies = cookiesOf(b)
    val aWarn = warningsOf(a)
    val bWarn = warningsOf(b)

    println("| 指标                | A 空 CookieJar | B 注入 Cookie |")
    println("|---------------------|----------------|---------------|")
    println("| Network.getCookies  | ${aCookies.size} 条            | ${bCookies.size} 条            |")
    println("| 源发出的登录警告    | ${aWarn.size} 条            | ${bWarn.size} 条            |")
    println("| hasCookieLogin      | ${boolOf(a, "account", "hasCookieLogin")}         | ${boolOf(b, "account", "hasCookieLogin")}         |")

    if (bCookies.isNotEmpty() && bWarn.isEmpty() && aCookies.isEmpty()) {
        println("\n判定: PASS —— 源在宿主注入 Cookie 后即认为已登录，")
        println("      loginWithWebview 可被「手工导入 Cookie」完全替代。")
    } else if (bCookies.isNotEmpty()) {
        println("\n判定: PARTIAL —— Cookie 已进入 CookieJar，但源仍有警告，需检查字段名或值格式。")
    } else {
        println("\n判定: FAIL —— 注入的 Cookie 未被源观察到。")
    }

    // 可选：带 Cookie 发真实请求，验证注入是否真的落到 HTTP 头上
    if (args.contains("--live")) {
        println("\n================ C. 带 Cookie 的真实请求 ================")
        val raw = VeneraSourceRunner(http, seed).run(code, "test")
        val c = Json.parseToJsonElement(raw).jsonObject
        val hostLogs = runCatching {
            c["hostLogs"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" }
        }.getOrNull().orEmpty()
        if (hostLogs.isEmpty()) {
            println("（源在 search 阶段未发出请求）")
        } else {
            hostLogs.forEach { println("  · $it") }
        }
        println("search error: ${c.str("error") ?: "<null>"}")
    }
}

private suspend fun probe(
    http: SourceHttp,
    code: String,
    seed: Map<String, Map<String, String>>,
): JsonObject {
    val raw = VeneraSourceRunner(http, seed).run(code, "", probeOnly = true)
    return Json.parseToJsonElement(raw).jsonObject
}

private fun dump(tag: String, o: JsonObject) {
    println("cookiesSeen = ${cookiesOf(o)}")
    println("account     = ${o.objOrNull("account")}")
    val logs = logsOf(o)
    println("源日志(${logs.size} 条):")
    logs.forEach { println("  · $it") }
    val hostLogs = runCatching {
        o["hostLogs"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" }
    }.getOrNull().orEmpty()
    if (hostLogs.isNotEmpty()) {
        println("请求日志(${hostLogs.size} 条):")
        hostLogs.forEach { println("  · $it") }
    }
}

private fun cookiesOf(o: JsonObject): List<String> = runCatching {
    o["cookiesSeen"]?.jsonArray?.mapNotNull { c ->
        runCatching {
            val x = c.jsonObject
            "${x.str("name")}=${x.str("value")}"
        }.getOrNull()
    }
}.getOrNull().orEmpty()

private fun logsOf(o: JsonObject): List<String> = runCatching {
    o["logs"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull ?: "" }
}.getOrNull().orEmpty()

private fun warningsOf(o: JsonObject): List<String> =
    logsOf(o).filter { it.contains("No cf_clearance", true) || it.contains("may need to login", true) }

private fun hostOf(url: String): String =
    url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()

private fun JsonObject.objOrNull(key: String): JsonObject? =
    runCatching { this[key]?.jsonObject }.getOrNull()

private fun JsonObject.str(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun boolOf(o: JsonObject, parent: String, key: String): Boolean =
    runCatching { o[parent]?.jsonObject?.get(key)?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
