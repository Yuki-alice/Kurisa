package data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceHttpClientTest {

    private val domains = setOf("cdn.example.com")

    private fun client(
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        status: HttpStatusCode = HttpStatusCode.OK,
        onRequest: ((String?) -> Unit)? = null,
    ): SourceHttpClient {
        val cookies = CookieStore().apply {
            put(seedCookie("cdn.example.com", "session", "s1", 0L))
        }
        val engine = MockEngine { request ->
            onRequest?.invoke(request.headers[HttpHeaders.Cookie])
            respond(
                content = ByteReadChannel(bytes),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
            )
        }
        return SourceHttpClient(HttpClient(engine), cookies) { 0L }
    }

    @Test
    fun bytesRoundTripWithCookie() = runTest {
        var sentCookie: String? = null
        val c = client(onRequest = { sentCookie = it })
        val r = c.getBytes("https://cdn.example.com/i/1.jpg", domains)
        assertEquals(HttpStatusCode.OK.value, r.status)
        assertEquals(listOf<Byte>(1, 2, 3), r.bytes.toList())
        assertTrue(sentCookie?.contains("session=s1") == true)
        assertEquals("cdn.example.com", r.audit.host)
    }

    @Test
    fun whitelistRejects() = runTest {
        val c = client()
        assertFailsWith<IllegalArgumentException> {
            c.getBytes("https://evil.com/x.jpg", domains)
        }
    }
}
