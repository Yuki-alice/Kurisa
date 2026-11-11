package spike

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ktor 行为：本地 302 重定向 + 白名单二次校验（desktop）。
 * commonTest 已锁规则三端一致；这里证明真实 engine 会跟随重定向，
 * 且宿主在每一跳都可用同一套 UrlPolicy 复核。
 */
class KtorRedirectTest {

    @Test
    fun cioFollowsRedirectAndPolicyRechecks() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = server.address.port
        server.createContext("/a") { ex: HttpExchange ->
            ex.responseHeaders.add("Location", "/b?page=2")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/b") { ex: HttpExchange ->
            val body = """{"ok":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            // 真实 skill：Ktor CIO 默认跟随重定向
            val client = HttpClient(CIO) { followRedirects = true }
            try {
                val resp = client.get("http://127.0.0.1:$port/a")
                assertEquals(HttpStatusCode.OK, resp.status)
                assertTrue(resp.bodyAsText().contains("ok"))
            } finally {
                client.close()
            }
            // 宿主侧用同一套规则复核每一跳（https 场景用 domains 白名单）
            val domains = setOf("api.mangadex.org")
            val v = UrlPolicy.validateRedirect(
                "https://api.mangadex.org/a", "https://api.mangadex.org/b", domains
            )
            assertTrue(v.ok)
        } finally {
            server.stop(0)
        }
    }
}
