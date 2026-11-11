package data.images

import data.network.CookieStore
import data.network.seedCookie
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

class ImageFetcherTest {

    private fun http(
        bytes: ByteArray = byteArrayOf(10, 20, 30),
        status: HttpStatusCode = HttpStatusCode.OK,
        seen: MutableList<Pair<String, String?>> = mutableListOf(),
    ): SourceImageFetcher {
        val cookies = CookieStore().apply {
            put(seedCookie("cdn.example.com", "session", "s1", 0L))
        }
        val engine = MockEngine { request ->
            seen += request.url.toString() to request.headers[HttpHeaders.Cookie]
            respond(
                content = ByteReadChannel(bytes),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
            )
        }
        return SourceImageFetcher(data.network.SourceHttpClient(HttpClient(engine), cookies) { 0L })
    }

    @Test
    fun bytesWithCookieAndWhitelist() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        val f = http(seen = seen)
        val r = f.fetch(ImageRequest("https://cdn.example.com/i/1.jpg", setOf("cdn.example.com")))
        assertEquals(listOf<Byte>(10, 20, 30), r.bytes.toList())
        assertEquals("https://cdn.example.com/i/1.jpg", r.finalUrl)
        assertTrue(seen.single().second?.contains("session=s1") == true)
    }

    @Test
    fun offAllowlistNeverRequested() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        val f = http(seen = seen)
        assertFailsWith<IllegalArgumentException> {
            f.fetch(ImageRequest("https://evil.com/x.jpg", setOf("cdn.example.com")))
        }
        assertTrue(seen.isEmpty())
    }

    @Test
    fun transformAndRetry() = runTest {
        var calls = 0
        val cookies = CookieStore()
        val engine = MockEngine {
            calls++
            if (calls == 1) {
                respond(
                    content = ByteReadChannel(byteArrayOf(1)),
                    status = HttpStatusCode.ServiceUnavailable,
                )
            } else {
                respond(
                    content = ByteReadChannel(byteArrayOf(5, 6)),
                    status = HttpStatusCode.OK,
                )
            }
        }
        val f = SourceImageFetcher(data.network.SourceHttpClient(HttpClient(engine), cookies) { 0L })
        val r = f.fetch(
            ImageRequest(
                "https://cdn.example.com/i/2.jpg",
                setOf("cdn.example.com"),
                config = ImageLoadingConfig(
                    transform = { bytes -> bytes.map { (it + 1).toByte() }.toByteArray() },
                    maxRetries = 2,
                ),
            ),
        )
        assertEquals(2, calls)
        assertEquals(listOf<Byte>(6, 7), r.bytes.toList())
    }

    @Test
    fun nonRetriableStillRejected() = runTest {
        val f = http(status = HttpStatusCode.NotFound)
        assertFailsWith<ImageFetchException> {
            f.fetch(ImageRequest("https://cdn.example.com/nope.jpg", setOf("cdn.example.com")))
        }
    }
}
