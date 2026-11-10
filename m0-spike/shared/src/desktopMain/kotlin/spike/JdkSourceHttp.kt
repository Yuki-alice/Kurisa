package spike

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 桌面端网络实现：JDK 原生 HttpClient。
 *
 * 用 Ktor CIO 时与 api.mangadex.org 握手失败
 * （TLSException: Received alert during handshake, code: ProtocolVersion），
 * JDK 栈与 curl 行为一致，能正常协商。此处仅作 M0 验证用，
 * 正式工程仍按架构走 Ktor + 平台 engine。
 */
class JdkSourceHttp : SourceHttp {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    override suspend fun fetch(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): SourceHttpResponse = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))

        headers.forEach { (k, v) ->
            // JDK HttpClient 禁止手动设置 Host / Content-Length
            if (!k.equals("Host", true) && !k.equals("Content-Length", true)) {
                runCatching { builder.header(k, v) }
            }
        }

        val publisher = if (body != null) {
            HttpRequest.BodyPublishers.ofString(body)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        builder.method(method.uppercase(), publisher)

        val resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        // 保留响应头：Cloudflare 的 `cf-mitigated` 是判定 challenge 的关键信号
        val respHeaders = resp.headers().map().entries.associate { (k, v) ->
            k.lowercase() to v.joinToString(", ")
        }
        SourceHttpResponse(resp.statusCode(), resp.body() ?: "", respHeaders)
    }
}
