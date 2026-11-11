package data.network

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
 * Ktor 行为：本地 302 + 宿主逐跳白名单复核（desktop）。
 * commonTest 已锁规则三端一致；此处证明真实 engine 与宿主策略协同。
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
            val client = HttpClient(CIO) { followRedirects = true }
            try {
                val resp = client.get("http://127.0.0.1:$port/a")
                assertEquals(HttpStatusCode.OK, resp.status)
                assertTrue(resp.bodyAsText().contains("ok"))
            } finally {
                client.close()
            }
            val domains = setOf("api.example.com")
            val v = UrlPolicy.validateRedirect(
                "https://api.example.com/a", "https://api.example.com/b", domains
            )
            assertTrue(v.ok)
        } finally {
            server.stop(0)
        }
    }
}
