package source.bridge

import data.network.CookieStore
import data.network.SourceHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * P1-2 验收：全方法 + 回传 headers/finalUrl/verdict/truncated + hostCall 映射。
 * 旧 JS 只读 status/body 仍兼容；新字段为增量（HOST-SDK.md §1）。
 */
class P1NetworkComplementTest {

    private class Capture {
        var method: HttpMethod? = null
        var url: String? = null
    }

    private fun bundle(
        body: String = """{"ok":true}""",
        headers: io.ktor.http.Headers = headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            "X-Site" to listOf("ok"),
        ),
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: Capture = Capture(),
    ): Triple<QuickJsEngine, InMemoryHostStorage, List<String>> {
        val logs = mutableListOf<String>()
        val cookies = CookieStore()
        val mock = MockEngine { request ->
            capture.method = request.method
            capture.url = request.url.toString()
            respond(content = ByteReadChannel(body), status = status, headers = headers)
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val network: HostNetwork = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
        val host = object : HostApi {
            override val network: HostNetwork = network
            override val html: HostHtml = KsoupHostHtml()
            override val convert: HostConvert = CryptoHostConvert()
            override val ui: HostUi = NoopHostUi()
            override val utils: HostUtils = HostUtilsImpl { logs += it }
            override val storage: HostStorage = InMemoryHostStorage()
            override val novel: HostNovel = NovelHost()
        }
        val engine = QuickJsEngine(
            sourceKey = "com.example.p1",
            host = host,
            cookies = cookies,
            dispatchers = DefaultSourceDispatchers(),
            evalTimeoutMs = 10_000,
        )
        return Triple(engine, host.storage as InMemoryHostStorage, logs)
    }

    @Test
    fun putPatchDeleteGoThrough() = runTest {
        val cap = Capture()
        val (e, _, _) = bundle(capture = cap)
        try {
            e.load("class T extends ComicSource {}")
            val put = Json.parseToJsonElement(
                e.callAsync("request", """{"method":"PUT","url":"https://cdn.example.com/a","headers":{},"data":"x"}""")
            ).jsonObject
            assertEquals(200, put["status"]?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals(HttpMethod.Put, cap.method)

            val patch = Json.parseToJsonElement(
                e.callAsync("request", """{"method":"PATCH","url":"https://cdn.example.com/a","headers":{},"data":"x"}""")
            ).jsonObject
            assertEquals(200, patch["status"]?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals(HttpMethod.Patch, cap.method)

            val del = Json.parseToJsonElement(
                e.callAsync("request", """{"method":"DELETE","url":"https://cdn.example.com/a","headers":{}}""")
            ).jsonObject
            assertEquals(200, del["status"]?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals(HttpMethod.Delete, cap.method)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun headersFinalUrlVerdictPassthrough() = runTest {
        val (e, _, _) = bundle()
        try {
            e.load("class T extends ComicSource {}")
            val raw = e.callAsync("request", """{"method":"GET","url":"https://cdn.example.com/a","headers":{}}""")
            val o = Json.parseToJsonElement(raw).jsonObject
            assertEquals("ok", o["headers"]?.jsonObject?.get("X-Site")?.jsonPrimitive?.contentOrNull)
            assertEquals("https://cdn.example.com/a", o["finalUrl"]?.jsonPrimitive?.contentOrNull)
            assertEquals("NONE", o["verdict"]?.jsonPrimitive?.contentOrNull)
            assertEquals(false, o["truncated"]?.jsonPrimitive?.contentOrNull?.toBoolean())
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun largeBodyTruncatesWithFlagAndDesensitizedLog() = runTest {
        val big = "x".repeat(QuickJsEngine.MAX_BODY_CHARS + 10)
        val (e, _, logs) = bundle(body = big)
        try {
            e.load("class T extends ComicSource {}")
            val raw = e.callAsync("request", """{"method":"GET","url":"https://cdn.example.com/a","headers":{}}""")
            val o = Json.parseToJsonElement(raw).jsonObject
            assertEquals(true, o["truncated"]?.jsonPrimitive?.contentOrNull?.toBoolean())
            assertEquals(QuickJsEngine.MAX_BODY_CHARS, o["body"]?.jsonPrimitive?.contentOrNull?.length)
            assertTrue(logs.any { it.contains("截断") && it.contains("com.example.p1") }, logs.toString())
            assertTrue(logs.none { "x".repeat(100) in it })
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun hostCallNetworkGetMapping() = runTest {
        val cap = Capture()
        val (e, _, _) = bundle(capture = cap)
        try {
            e.load("class T extends ComicSource {}")
            val raw = e.callAsync(
                "hostCall",
                """{"method":"network.get","url":"https://cdn.example.com/a","headers":{}}"""
            )
            val o = Json.parseToJsonElement(raw).jsonObject
            assertEquals(200, o["status"]?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals(HttpMethod.Get, cap.method)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun hostCallUnknownIsPermissionCoded() = runTest {
        val (e, _, _) = bundle()
        try {
            e.load("class T extends ComicSource {}")
            val raw = e.callAsync("hostCall", """{"method":"nope"}""")
            val o = Json.parseToJsonElement(raw).jsonObject
            assertEquals("unsupported-hostcall", o["error"]?.jsonPrimitive?.contentOrNull)
            assertEquals("PERMISSION", o["code"]?.jsonPrimitive?.contentOrNull)
            val badOp = e.callSync("html", """{"op":"bogus"}""")
            val b = Json.parseToJsonElement(badOp).jsonObject
            assertEquals("CONTRACT", b["code"]?.jsonPrimitive?.contentOrNull)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun selectManyMatchesSeparateSelects() = runTest {
        val (e, _, _) = bundle(body = """{"ok":true}""")
        try {
            e.load("class T extends ComicSource {}")
            val html = "<div><p class=\"a\">A</p><p class=\"b\">B</p></div>"
            val esc = html.replace("\\", "\\\\").replace("\"", "\\\"")
            val parse = Json.parseToJsonElement(
                e.callSync("html", """{"op":"parse","html":"$esc"}""")
            ).jsonObject
            val doc = parse["doc"]?.jsonPrimitive?.contentOrNull ?: error("no doc")
            val singleA = e.callSync("html", """{"op":"select","doc":$doc,"css":".a"}""")
            val singleB = e.callSync("html", """{"op":"select","doc":$doc,"css":".b"}""")
            // P1：一次跨边界多选择器，结果与逐个 select 一致（跨边界次数从 N 降到 1）。
            val many = e.callSync("html", """{"op":"selectMany","doc":$doc,"queries":[".a",".b"]}""")
            val arr = Json.parseToJsonElement(many) as kotlinx.serialization.json.JsonArray
            assertEquals(2, arr.size)
            assertEquals(singleA, arr[0].toString())
            assertEquals(singleB, arr[1].toString())
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }

    @Test
    fun requestAllPreservesOrderAndDedupes() = runTest {
        var calls = 0
        val urls = mutableListOf<String>()
        val cookies = CookieStore()
        val mock = MockEngine { request ->
            calls++
            urls += request.url.toString()
            respond(
                content = ByteReadChannel("""{"u":"${request.url.encodedPath}"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val network = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
        val reqs = listOf(
            source.hostapi.HostRequest("GET", "https://cdn.example.com/a"),
            source.hostapi.HostRequest("GET", "https://cdn.example.com/b"),
            source.hostapi.HostRequest("GET", "https://cdn.example.com/a"),
        )
        val res = network.requestAll(reqs)
        assertEquals(3, res.size)
        assertTrue(res[0].body.contains("/a"))
        assertTrue(res[1].body.contains("/b"))
        assertEquals(res[0].body, res[2].body)
        // in-flight 去重 + 缓存：3 条逻辑请求（含重复）只产生 2 次真实网络请求。
        // 并发下到达顺序不保证，只断言集合与次数；结果保序已在上方断言。
        assertEquals(2, calls)
        assertEquals(
            setOf("https://cdn.example.com/a", "https://cdn.example.com/b"),
            urls.toSet(),
        )
        assertEquals(2, urls.size)
    }

    @Test
    fun getCacheHitAndPostNoCache() = runTest {
        var calls = 0
        val cookies = CookieStore()
        val mock = MockEngine {
            calls++
            respond(
                content = ByteReadChannel("""{"n":$calls}"""),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type" to listOf("application/json")),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val network = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
        val first = network.get("https://cdn.example.com/c", emptyMap())
        val second = network.get("https://cdn.example.com/c", emptyMap())
        assertEquals(first.body, second.body)
        assertEquals(1, calls)
        // 写操作永不缓存：两次 POST 各发一次。
        network.post("https://cdn.example.com/c", emptyMap(), "x")
        network.post("https://cdn.example.com/c", emptyMap(), "x")
        assertEquals(3, calls)
    }

    @Test
    fun bridgeRequestAllEndToEnd() = runTest {
        val (e, _, _) = bundle()
        try {
            e.load("class T extends ComicSource {}")
            val raw = e.callAsync(
                "requestAll",
                """{"requests":[
                    {"method":"GET","url":"https://cdn.example.com/a","headers":{}},
                    {"method":"GET","url":"https://cdn.example.com/b","headers":{}}
                ]}"""
            )
            val arr = Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray
            assertEquals(2, arr.size)
            assertEquals(200, arr[0].jsonObject["status"]?.jsonPrimitive?.contentOrNull?.toInt())
            assertEquals("NONE", arr[0].jsonObject["verdict"]?.jsonPrimitive?.contentOrNull)
        } catch (mh: HostMissingException) {
            println("SKIP（无 quickjs native）: " + mh.message)
        } finally {
            e.close()
        }
    }
}
