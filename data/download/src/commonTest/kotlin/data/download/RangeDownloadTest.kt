package data.download

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangeDownloadTest {

    private class FakeFetcher(
        val expectFrom: Long = 0,
        val status206: Boolean = true,
        var seenRange: String? = null,
    ) : ByteFetcher {
        override suspend fun fetch(
            url: String,
            domains: Set<String>,
            fromByte: Long,
            headers: Map<String, String>,
        ): ByteFetcher.FetchResult {
            assertEquals(expectFrom, fromByte)
            seenRange = if (fromByte > 0) "bytes=$fromByte-" else null
            return ByteFetcher.FetchResult(
                bytes = byteArrayOf(9, 9),
                total = 100,
                resumed = status206 && fromByte > 0,
            )
        }
    }

    @Test
    fun resumeOffsetAndProgress() = runTest {
        val fake = FakeFetcher(expectFrom = 60)
        var done = -1L
        var total: Long? = null
        val ex = RangeDownloadExecutor(fake) { d, t -> done = d; total = t }
        val r = ex.download("https://cdn.example.com/f.bin", setOf("cdn.example.com"), fromByte = 60)
        assertEquals("bytes=60-", fake.seenRange)
        assertTrue(r.resumed)
        assertEquals(62, done)
        assertEquals(100, total)
    }

    @Test
    fun fullResponseIgnoresOffset() = runTest {
        val fake = FakeFetcher(expectFrom = 60, status206 = false)
        val r = RangeDownloadExecutor(fake).download(
            "https://cdn.example.com/f.bin", setOf("cdn.example.com"), fromByte = 60
        )
        assertFalse(r.resumed)
        assertEquals(2, r.bytes.size)
    }

    @Test
    fun negativeOffsetRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            RangeDownloadExecutor(FakeFetcher()).download(
                "https://cdn.example.com/f.bin", setOf("cdn.example.com"), fromByte = -1
            )
        }
    }

    @Test
    fun ktorFetcherRangeAndCookie() = runTest {
        var seenRange: String? = null
        var seenCookie: String? = null
        val cookies = CookieStore().apply {
            put(seedCookie("cdn.example.com", "session", "s1", 0L))
        }
        val engine = MockEngine { request ->
            seenRange = request.headers[HttpHeaders.Range]
            seenCookie = request.headers[HttpHeaders.Cookie]
            respond(
                content = ByteReadChannel(byteArrayOf(7, 7, 7)),
                status = HttpStatusCode.PartialContent,
                headers = headersOf(HttpHeaders.ContentRange, "bytes 60-62/100"),
            )
        }
        val f = KtorRangeFetcher(HttpClient(engine), cookies, nowMs = { 0L })
        val r = f.fetch("https://cdn.example.com/f.bin", setOf("cdn.example.com"), 60)
        assertEquals("bytes=60-", seenRange)
        assertTrue(seenCookie?.contains("session=s1") == true)
        assertTrue(r.resumed)
        assertEquals(100, r.total)
        assertEquals(3, r.bytes.size)
    }

    @Test
    fun ktorFetcherWhitelistRejects() = runTest {
        val engine = MockEngine {
            respond(content = ByteReadChannel(byteArrayOf(1)), status = HttpStatusCode.OK)
        }
        val f = KtorRangeFetcher(HttpClient(engine), CookieStore(), nowMs = { 0L })
        assertFailsWith<IllegalArgumentException> {
            f.fetch("https://evil.com/f.bin", setOf("cdn.example.com"), 0)
        }
    }
}
