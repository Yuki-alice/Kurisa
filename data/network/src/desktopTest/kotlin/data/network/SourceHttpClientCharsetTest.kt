package data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 响应体编码协商（desktopTest：JDK 的 GBK 编码器造字节）。
 * 价值：中文老站只在 HTML meta 里声明 charset，头里没有——必须能嗅探并正确解码，否则整站乱码。
 */
class SourceHttpClientCharsetTest {

    private val domains = setOf("example.com")

    private suspend fun body(content: ByteArray, contentType: String?): String {
        val engine = MockEngine {
            respond(
                ByteReadChannel(content),
                HttpStatusCode.OK,
                if (contentType == null) headersOf() else headersOf(HttpHeaders.ContentType, contentType),
            )
        }
        val client = SourceHttpClient(HttpClient(engine), CookieStore()) { 0L }
        val r = client.get("https://example.com/x", domains, emptyMap())
        return r.body
    }

    @Test
    fun decodesGbkFromContentTypeHeader() = runTest {
        val text = "轻小说文库"
        val out = body(text.toByteArray(Charset.forName("GBK")), "text/html; charset=gbk")
        assertEquals(text, out)
    }

    @Test
    fun decodesGbkFromHtmlMetaWhenHeaderSilent() = runTest {
        val html = """<html><head><meta charset="gbk"><title>轻小说文库</title></head><body>正文</body></html>"""
        val out = body(html.toByteArray(Charset.forName("GBK")), "text/html")
        assertTrue(out.contains("轻小说文库") && out.contains("正文"), out)
    }

    @Test
    fun keepsUtf8BehaviourForUtf8Sites() = runTest {
        val html = """<html><head><meta charset="utf-8"></head><body>日本語テキスト</body></html>"""
        assertEquals(html, body(html.toByteArray(Charsets.UTF_8), "text/html"))
        assertEquals(html, body(html.toByteArray(Charsets.UTF_8), "text/html; charset=utf-8"))
    }
}
