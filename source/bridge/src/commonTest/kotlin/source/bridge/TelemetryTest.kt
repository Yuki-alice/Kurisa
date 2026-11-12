package source.bridge

import data.network.CookieStore
import data.network.SourceHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import source.hostapi.HostApi
import source.hostapi.HostConvert
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3 可观测性底座（`DESIGN.md` §8 + `HOST-SDK.md` § Telemetry）。
 * 三段按层归属：JS（engine）/ net（host network）/ parse（host html）；
 * 只记调用计数与耗时，不记 URL/正文/Cookie（审计脱敏红线）。
 */
class TelemetryTest {

    private fun hostWith(counter: MutableList<String>): Triple<HostApi, CookieStore, HostNetwork> {
        val cookies = CookieStore()
        val mock = MockEngine { request ->
            counter += request.url.toString()
            respond(
                content = ByteReadChannel("""{"ok":true}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val network: HostNetwork = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
        val host = object : HostApi {
            override val network: HostNetwork = network
            override val html: HostHtml = KsoupHostHtml()
            override val convert: HostConvert = CryptoHostConvert()
            override val ui: HostUi = NoopHostUi()
            override val utils: HostUtils = HostUtilsImpl()
            override val storage: HostStorage = InMemoryHostStorage()
            override val novel: HostNovel = NovelHost()
        }
        return Triple(host, cookies, network)
    }

    @Test
    fun engineTelemetryCountsCallsAndErrors() = runTest {
        val (host, cookies, _) = hostWith(mutableListOf())
        val e = QuickJsEngine("com.example.t", host, cookies, DefaultSourceDispatchers())
        try {
            e.load("class T extends ComicSource { probe = async () => 'ok'; }")
            assertEquals(""""ok"""", e.invoke("probe", "[]"))
            assertEquals(""""ok"""", e.invoke("probe", "[]"))
            runCatching { e.invoke("missingFn", "[]") }
            val t = e.telemetry()
            assertEquals(3, t.calls)
            assertEquals(1, t.timeouts + t.errors)
            assertEquals(0, t.timeouts)
            assertEquals(1, t.errors)
            assertTrue(t.jsMsTotal >= 0)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun networkTelemetryCacheAndCoalescing() = runTest {
        val seen = mutableListOf<String>()
        val (host, _, network) = hostWith(seen)
        val kn = network as KtorHostNetwork
        kn.get("https://cdn.example.com/a", emptyMap())
        kn.get("https://cdn.example.com/a", emptyMap())
        // requestAll 并发同 key 合并 + 缓存命中：3 条逻辑调用只产生 ≤2 次真实请求。
        kn.requestAll(
            listOf(
                source.hostapi.HostRequest("GET", "https://cdn.example.com/b"),
                source.hostapi.HostRequest("GET", "https://cdn.example.com/b"),
                source.hostapi.HostRequest("GET", "https://cdn.example.com/a"),
            ),
        )
        val t = kn.networkTelemetry()
        assertEquals(5, t.requests)
        assertTrue(t.cacheHits >= 2, t.toString())
        assertEquals(2, seen.distinct().size)
        assertTrue(t.netMsTotal >= 0)
        assertTrue(host.network === network)
    }

    @Test
    fun htmlTelemetryCounts() {
        val html = KsoupHostHtml()
        val doc = html.parse("<div><p class=\"a\">A</p></div>")
        html.select(doc, ".a")
        html.select(doc, ".missing")
        html.selectMany(doc, listOf(".a", ".b"))
        html.dispose(doc)
        val t = html.htmlTelemetry()
        assertEquals(1, t.parses)
        assertEquals(2, t.selects)
        assertEquals(1, t.selectManys)
        assertEquals(1, t.disposes)
        assertEquals(0, html.liveCount())
        assertTrue(t.parseSelectMsTotal >= 0)
    }
}
