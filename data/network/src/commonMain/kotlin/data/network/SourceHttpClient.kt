package data.network

import core.model.SourceAuditEvent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * 源 HTTP 出口（架构第 8 章统一出口的产品化最小闭环）。
 *
 * 强制点（缺一不可）：
 * - 首跳与每一跳重定向都过 [UrlPolicy] 白名单；https→http 降级拒绝；最多 5 跳；
 * - Cookie 经 [CookieStore.headerFor] 注入；`cf_clearance` 命中时用配对 UA 覆盖本次 UA；
 * - 响应按 [CloudflareGuard] 分类，调用方据此决定重试/浏览器过盾/换网；
 * - 审计只记域名+状态（[SourceAuditEvent]），不记 Cookie 与正文（MASVS-STORAGE/隐私）。
 *
 * 非目标：重试/限流/熔断的声明式组合（kmp-resilient，M3 按需引入）；全局 fetch polyfill
 * 由 :source:bridge 包一层 `__hostCall`（M2 bridge 范围外，此处只给 Kotlin 调用方）。
 */
class SourceHttpClient(
    private val client: HttpClient,
    private val cookies: CookieStore,
    private val nowMs: () -> Long = { 0L },
) {
    data class FetchResult(
        val status: Int,
        val body: String,
        val finalUrl: String,
        val verdict: Verdict,
        val audit: SourceAuditEvent,
        val headers: Map<String, String> = emptyMap(),
    )

    /** 字节结果（图片/下载：与文本路径同一套白名单+Cookie+UA+审计不断言正文）。 */
    data class BytesResult(
        val status: Int,
        val bytes: ByteArray,
        val finalUrl: String,
        val verdict: Verdict,
        val audit: SourceAuditEvent,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BytesResult) return false
            return status == other.status && bytes.contentEquals(other.bytes) &&
                finalUrl == other.finalUrl && verdict == other.verdict && audit == other.audit
        }

        override fun hashCode(): Int {
            var r = status
            r = 31 * r + bytes.contentHashCode()
            r = 31 * r + finalUrl.hashCode()
            r = 31 * r + verdict.hashCode()
            r = 31 * r + audit.hashCode()
            return r
        }
    }

    suspend fun get(
        url: String,
        domains: Set<String>,
        headers: Map<String, String> = emptyMap(),
        defaultUserAgent: String = DEFAULT_UA,
    ): FetchResult = request("GET", url, domains, headers, null, defaultUserAgent)

    suspend fun post(
        url: String,
        domains: Set<String>,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        defaultUserAgent: String = DEFAULT_UA,
    ): FetchResult = request("POST", url, domains, headers, body, defaultUserAgent)

    suspend fun send(
        method: String,
        url: String,
        domains: Set<String>,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        defaultUserAgent: String = DEFAULT_UA,
    ): FetchResult {
        val m = method.uppercase()
        require(m in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) { "unsupported-method:$method" }
        return request(m, url, domains, headers, body, defaultUserAgent)
    }

    /**
     * 字节 GET（图片页/下载分片）。
     * 与文本路径共享全部强制点；Cloudflare 判定用正文前 20KB 的有损解码
     * （挑战页为 HTML 文本，图片字节误判时调用方以状态码为准）。
     */
    suspend fun getBytes(
        url: String,
        domains: Set<String>,
        headers: Map<String, String> = emptyMap(),
        defaultUserAgent: String = DEFAULT_UA,
    ): BytesResult {
        require(UrlPolicy.isAllowed(url, domains)) { "domain-blocked:$url" }
        var current = url
        var hops = 0
        while (true) {
            val ua = cookies.pairedUserAgentFor(current, nowMs()) ?: defaultUserAgent
            val resp: HttpResponse = client.get(current) {
                header(HttpHeaders.UserAgent, ua)
                cookies.headerFor(current, nowMs())?.let { header(HttpHeaders.Cookie, it) }
                headers.forEach { (k, v) ->
                    if (!k.equals(HttpHeaders.UserAgent, true) && !k.equals(HttpHeaders.Cookie, true)) {
                        header(k, v)
                    }
                }
            }
            val status = resp.status.value
            if (status in 301..308) {
                val loc = resp.headers[HttpHeaders.Location].orEmpty()
                val v = UrlPolicy.validateRedirect(current, loc, domains)
                require(v.ok) { v.reason }
                require(++hops <= UrlPolicy.MAX_HOPS) { "too-many-redirects" }
                current = v.reason
                continue
            }
            val bytes = runCatching { resp.bodyAsBytes() }.getOrElse { ByteArray(0) }
            val headerMap = resp.headers.entries().associate { it.key to it.value.joinToString(",") }
            val verdict = CloudflareGuard.classify(status, headerMap, bytes.decodeToString())
            val audit = SourceAuditEvent(
                sourceKey = "",
                host = UrlPolicy.hostOf(current),
                status = status,
                atEpochMs = nowMs(),
            )
            return BytesResult(status, bytes, current, verdict, audit)
        }
    }

    private suspend fun request(
        method: String,
        url: String,
        domains: Set<String>,
        headers: Map<String, String>,
        body: String?,
        defaultUserAgent: String,
    ): FetchResult {
        require(UrlPolicy.isAllowed(url, domains)) { "domain-blocked:$url" }
        var current = url
        var hops = 0
        // 手动跟随重定向：Ktor 引擎默认行为各端不一致，宿主统一复核每一跳
        while (true) {
            val ua = cookies.pairedUserAgentFor(current, nowMs()) ?: defaultUserAgent
            val resp: HttpResponse = when (method) {
                "POST" -> client.post(current) {
                    header(HttpHeaders.UserAgent, ua)
                    cookies.headerFor(current, nowMs())?.let { header(HttpHeaders.Cookie, it) }
                    headers.forEach { (k, v) ->
                        // UA 与 Cookie 由宿主统一注入，调用方不得覆盖（防绕过审计）
                        if (!k.equals(HttpHeaders.UserAgent, true) && !k.equals(HttpHeaders.Cookie, true)) {
                            header(k, v)
                        }
                    }
                    if (body != null) setBody(body)
                }
                "PUT" -> client.put(current) {
                    header(HttpHeaders.UserAgent, ua)
                    cookies.headerFor(current, nowMs())?.let { header(HttpHeaders.Cookie, it) }
                    headers.forEach { (k, v) ->
                        if (!k.equals(HttpHeaders.UserAgent, true) && !k.equals(HttpHeaders.Cookie, true)) {
                            header(k, v)
                        }
                    }
                    if (body != null) setBody(body)
                }
                "PATCH" -> client.patch(current) {
                    header(HttpHeaders.UserAgent, ua)
                    cookies.headerFor(current, nowMs())?.let { header(HttpHeaders.Cookie, it) }
                    headers.forEach { (k, v) ->
                        if (!k.equals(HttpHeaders.UserAgent, true) && !k.equals(HttpHeaders.Cookie, true)) {
                            header(k, v)
                        }
                    }
                    if (body != null) setBody(body)
                }
                "DELETE" -> client.delete(current) {
                    header(HttpHeaders.UserAgent, ua)
                    cookies.headerFor(current, nowMs())?.let { header(HttpHeaders.Cookie, it) }
                    headers.forEach { (k, v) ->
                        if (!k.equals(HttpHeaders.UserAgent, true) && !k.equals(HttpHeaders.Cookie, true)) {
                            header(k, v)
                        }
                    }
                }
                else -> client.get(current) {
                    header(HttpHeaders.UserAgent, ua)
                    cookies.headerFor(current, nowMs())?.let { header(HttpHeaders.Cookie, it) }
                    headers.forEach { (k, v) ->
                        if (!k.equals(HttpHeaders.UserAgent, true) && !k.equals(HttpHeaders.Cookie, true)) {
                            header(k, v)
                        }
                    }
                }
            }
            val status = resp.status.value
            if (status in 301..308) {
                val loc = resp.headers[HttpHeaders.Location].orEmpty()
                val v = UrlPolicy.validateRedirect(current, loc, domains)
                require(v.ok) { v.reason }
                require(++hops <= UrlPolicy.MAX_HOPS) { "too-many-redirects" }
                current = v.reason
                continue
            }
            // 编码兜底：优先响应头 charset，其次 HTML meta；GBK 族走内置解码器，
            // 其余（含 UTF-8）沿用 UTF-8 解码（KMP 公共 API 无平台 Charset，见 GbkCodec 注释）。
            val bytes = runCatching { resp.bodyAsBytes() }.getOrElse { ByteArray(0) }
            val text = runCatching {
                decodeBody(bytes, resp.headers[HttpHeaders.ContentType])
            }.getOrElse { "" }
            val headerMap = resp.headers.entries().associate { it.key to it.value.joinToString(",") }
            val verdict = CloudflareGuard.classify(status, headerMap, text)
            val audit = SourceAuditEvent(
                sourceKey = "",
                host = UrlPolicy.hostOf(current),
                status = status,
                atEpochMs = nowMs(),
            )
            return FetchResult(status, text, current, verdict, audit, headerMap)
        }
    }

    /** charset 判定与解码（GBK 族内置表，其余 UTF-8；均以 U+FFFD 替换非法序列，不抛异常）。 */
    private fun decodeBody(bytes: ByteArray, contentType: String?): String {
        val declared = charsetOf(contentType) ?: sniffMetaCharset(bytes)
        return if (declared != null && GbkCodec.isGbkFamily(declared)) {
            GbkCodec.decode(bytes)
        } else {
            bytes.decodeToString()
        }
    }

    private fun charsetOf(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val m = Regex("""charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE).find(contentType)
        return m?.groupValues?.getOrNull(1)
    }

    /** 头里没有 charset 时嗅探 HTML 头部声明（只看前 2KB，按单字节映射避免误解码）。 */
    private fun sniffMetaCharset(bytes: ByteArray): String? {
        val head = bytes.take(2048).joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
        val m = Regex("""charset\s*=\s*["']?([A-Za-z0-9_\-]+)""", RegexOption.IGNORE_CASE).find(head)
        return m?.groupValues?.getOrNull(1)
    }

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0"
    }
}

/** 默认 HttpClient（各引擎由 sourceSet 依赖解析；此处只装通用插件，不记敏感日志）。 */
fun defaultHttpClient(): HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
    }
    // 关键：关闭自动重定向，交由 SourceHttpClient 逐跳白名单复核
    followRedirects = false
}

/** body() 快捷（保留未用时提示）。 */
@Suppress("unused")
suspend inline fun <reified T> HttpResponse.jsonBody(): T = body()
