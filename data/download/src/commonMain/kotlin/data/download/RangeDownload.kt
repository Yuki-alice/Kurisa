package data.download

import data.network.CookieStore
import data.network.UrlPolicy
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode

/**
 * 字节抓取（DIY-Ktor Range 后端；Ketch 到位后替换本实现，接口不变）。
 *
 * 暂停/续传模型：调用方持有已收字节数，取消协程即暂停，
 * 携带新偏移重调即续传（`Range: bytes=<offset>-`）。
 */
interface ByteFetcher {
    data class FetchResult(
        /** 本次返回的字节（206 为分片，200 为全量——此时忽略偏移）。 */
        val bytes: ByteArray,
        /** 文件总长（Content-Range 解析，无则 null）。 */
        val total: Long?,
        val resumed: Boolean,
    )

    suspend fun fetch(
        url: String,
        domains: Set<String>,
        fromByte: Long,
        headers: Map<String, String> = emptyMap(),
    ): FetchResult
}

/** 执行器：单次抓取 + 进度回调（多次续传由调用方编排）。 */
class RangeDownloadExecutor(
    private val fetcher: ByteFetcher,
    private val onProgress: suspend (doneBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
) {
    data class Result(val bytes: ByteArray, val total: Long?, val resumed: Boolean)

    suspend fun download(
        url: String,
        domains: Set<String>,
        fromByte: Long = 0,
        headers: Map<String, String> = emptyMap(),
    ): Result {
        require(fromByte >= 0)
        val r = fetcher.fetch(url, domains, fromByte, headers)
        // 200 全量到达但带偏移：视为从头（调用方丢弃旧分片）。
        val done = if (r.resumed) fromByte + r.bytes.size else r.bytes.size.toLong()
        onProgress(done, r.total)
        return Result(r.bytes, r.total, r.resumed)
    }
}

/**
 * Ktor Range 抓取（与 `SourceHttpClient` 同等强制点：白名单首跳+逐跳复核、
 * Cookie 注入、配对 UA；重定向手动跟随，`followRedirects` 保持关闭由宿主复核）。
 */
class KtorRangeFetcher(
    private val client: HttpClient,
    private val cookies: CookieStore,
    private val nowMs: () -> Long = { 0L },
    private val defaultUserAgent: String = "Mozilla/5.0",
) : ByteFetcher {
    override suspend fun fetch(
        url: String,
        domains: Set<String>,
        fromByte: Long,
        headers: Map<String, String>,
    ): ByteFetcher.FetchResult {
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
                if (fromByte > 0) header(HttpHeaders.Range, "bytes=$fromByte-")
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
            require(status == HttpStatusCode.OK.value || status == HttpStatusCode.PartialContent.value) {
                "unexpected-status:$status"
            }
            val bytes = resp.bodyAsBytes()
            val total = resp.headers[HttpHeaders.ContentRange]
                ?.substringAfter('/', "")
                ?.toLongOrNull()
                ?: resp.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            return ByteFetcher.FetchResult(
                bytes = bytes,
                total = total,
                resumed = status == HttpStatusCode.PartialContent.value,
            )
        }
    }
}
