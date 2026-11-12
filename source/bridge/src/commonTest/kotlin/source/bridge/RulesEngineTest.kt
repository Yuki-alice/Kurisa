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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import source.contract.ContentKind
import source.contract.SourceCapability
import source.contract.SourceDescriptor
import source.contract.SourceErrorKind
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * P2 最小闭环：规则子集（HTML + CSS）端到端。
 * 覆盖：search/detail/images/text 四段、fileName/absUrl/trim、分页整数、
 * 缺段/空目录/非 200/非法 format 的 CONTRACT 语义、describe 存在位、
 * `BridgeChapterProvider` 同一归一化复用。
 */
class RulesEngineTest {

    private val searchHtml = """
<div class="book-li"><a href="/comic/w1.html">  T1  </a><img src="/c1.jpg"/></div>
<div class="book-li"><a href="/comic/w2.html">T2</a><img src="https://cdn.example.com/c2.jpg"/></div>
""".trimIndent()
    private val detailHtml = """
<html><body><h1>W1</h1>
<div class="cover"><img src="/cover.jpg"/></div>
<div id="chapters"><a href="/comic/w1/c1.html">第一话</a><a href="/comic/w1/c2.html">第二话</a></div>
</body></html>
""".trimIndent()
    private val contentHtml = """
<div id="images"><img data-src="/p1.jpg"/><img src="https://cdn.example.com/p2.jpg"/></div>
""".trimIndent()
    private val novelHtml = """
<div id="content"><p>第一段</p><script>alert(1)</script><p>第二段</p></div>
""".trimIndent()

    private fun mangaRules(): String = """
{
  "format": "rules/v1", "key": "ex.manga", "name": "EX", "version": "1.0.0",
  "kind": ["manga"], "domains": ["cdn.example.com"], "baseUrl": "https://cdn.example.com/",
  "search": {
    "request": {"method": "GET", "url": "/s?q=@keyword&page=@page"},
    "mode": "html", "list": ".book-li",
    "fields": {"id": "a@href | fileName", "title": "a@text | trim", "cover": "img@src | absUrl"},
    "pagination": {"maxPage": "5"}
  },
  "detail": {
    "request": {"url": "/comic/@id"}, "mode": "html",
    "title": "h1@text", "cover": ".cover img@src | absUrl",
    "chapters": {"list": "#chapters a", "id": "@href | fileName", "title": "@text | trim"}
  },
  "content": {"images": {"list": "#images img", "value": "@data-src|@src | absUrl"}}
}
""".trimIndent()

    private fun novelRules(): String = """
{
  "format": "rules/v1", "key": "ex.novel", "name": "EXN", "version": "1.0.0",
  "kind": ["novel"], "domains": ["cdn.example.com"], "baseUrl": "https://cdn.example.com/",
  "search": {
    "request": {"method": "GET", "url": "/s?q=@keyword&page=@page"},
    "mode": "html", "list": ".book-li",
    "fields": {"id": "a@href | fileName", "title": "a@text | trim"},
    "pagination": {"maxPage": "1"}
  },
  "detail": {
    "request": {"url": "/n/@id"}, "mode": "html",
    "title": "h1@text",
    "chapters": {"list": "#chapters a", "id": "@href | fileName", "title": "@text | trim"}
  },
  "content": {"text": {"select": "#content", "clean": true}}
}
""".trimIndent()

    private fun host(bodyFor: (String) -> Pair<HttpStatusCode, String>): HostApi {
        val cookies = CookieStore()
        val mock = MockEngine { request ->
            val p = request.url.encodedPath
            val (status, body) = bodyFor(p)
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val network: HostNetwork = KtorHostNetwork(http, cookies, setOf("cdn.example.com")) { 0L }
        return object : HostApi {
            override val network: HostNetwork = network
            override val html: HostHtml = KsoupHostHtml()
            override val convert: HostConvert = CryptoHostConvert()
            override val ui: HostUi = NoopHostUi()
            override val utils: HostUtils = HostUtilsImpl()
            override val storage: HostStorage = InMemoryHostStorage()
            override val novel: HostNovel = NovelHost()
        }
    }

    private fun mangaHost(): HostApi = host { p ->
        when {
            p.startsWith("/s") -> HttpStatusCode.OK to searchHtml
            p == "/comic/w1" -> HttpStatusCode.OK to detailHtml
            p == "/comic/w1/c1" || p == "/comic/w1/c1.html" -> HttpStatusCode.OK to contentHtml
            else -> HttpStatusCode.NotFound to ""
        }
    }

    @Test
    fun mangaSearchDetailImages() = runTest {
        val e = RulesEngine("ex.manga", mangaHost())
        e.load(mangaRules())
        val page = Json.parseToJsonElement(e.searchWorks("T", "[]", 2)).jsonObject
        val works = page["works"]!!.jsonArray
        assertEquals(2, works.size)
        assertEquals("w1", works[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("T1", works[0].jsonObject["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://cdn.example.com/c1.jpg", works[0].jsonObject["cover"]?.jsonPrimitive?.contentOrNull)
        assertEquals(5, page["maxPage"]?.jsonPrimitive?.contentOrNull?.toInt())

        val detail = Json.parseToJsonElement(e.loadWorkDetails("w1", ContentKind.MANGA)).jsonObject
        assertEquals("W1", detail["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://cdn.example.com/cover.jpg", detail["cover"]?.jsonPrimitive?.contentOrNull)
        val chapters = detail["chapters"]!!.jsonArray
        assertEquals(listOf("c1", "c2"), chapters.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull })

        val payload = Json.parseToJsonElement(e.loadChapterPayload("w1", "/comic/w1/c1.html", ContentKind.MANGA)).jsonObject
        assertEquals(
            listOf("https://cdn.example.com/p1.jpg", "https://cdn.example.com/p2.jpg"),
            payload["images"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
        )

        // 同一归一化复用：Bridge 直接消费规则引擎输出。
        val provider = BridgeChapterProvider(e, ContentKind.MANGA)
        assertEquals(listOf("c1", "c2"), provider.chapters("w1").map { it.id })
        val images = provider.payload("w1", "/comic/w1/c1.html")
        assertTrue(images is core.model.ChapterPayload.Images)
        assertEquals(2, (images as core.model.ChapterPayload.Images).urls.size)
    }

    @Test
    fun novelTextClean() = runTest {
        val h = host { p ->
            when {
                p.startsWith("/s") -> HttpStatusCode.OK to searchHtml
                p == "/n/n1" -> HttpStatusCode.OK to detailHtml
                p == "/n/n1/ch1" -> HttpStatusCode.OK to novelHtml
                else -> HttpStatusCode.NotFound to ""
            }
        }
        val e = RulesEngine("ex.novel", h)
        e.load(novelRules())
        val payload = Json.parseToJsonElement(e.loadChapterPayload("n1", "/n/n1/ch1", ContentKind.NOVEL)).jsonObject
        assertEquals("plain", payload["format"]?.jsonPrimitive?.contentOrNull)
        val body = payload["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(body.contains("第一段") && body.contains("第二段"), body)
        assertTrue(!body.contains("alert") && !body.contains("<script"), body)

        val provider = BridgeChapterProvider(e, ContentKind.NOVEL)
        val text = provider.payload("n1", "/n/n1/ch1")
        assertTrue(text is core.model.ChapterPayload.Text)
    }

    @Test
    fun describeCapabilities() = runTest {
        val e = RulesEngine("ex.manga", mangaHost())
        e.load(mangaRules())
        val d = SourceDescriptor.fromJson(e.describe(), emptySet(), 4)
        assertTrue(d.has(SourceCapability.SEARCH))
        assertTrue(d.has(SourceCapability.COMIC))
        assertEquals("ex.manga", d.key)
    }

    @Test
    fun missingSegmentIsContract() = runTest {
        val e = RulesEngine("ex.manga", mangaHost())
        val bad = mangaRules().replace("\"content\":", "\"_content\":")
        // load 期缺段即 CONTRACT（安装期可拦截，不等到调用才暴露）。
        // 实现选择：load 抛 SourceBridgeException（脚本违约），调用侧统一收敛为 CONTRACT；
        // 此处锁定 load 失败，避免坏包进入运行时缓存。
        assertFailsWith<SourceBridgeException> { e.load(bad) }
    }

    @Test
    fun non200IsContract() = runTest {
        val h = host { HttpStatusCode.NotFound to "" }
        val e = RulesEngine("ex.manga", h)
        e.load(mangaRules())
        val err = assertFailsWith<SourcePluginException> { e.searchWorks("T", "[]", 1) }
        assertEquals(SourceErrorKind.CONTRACT, err.kind)
    }

    @Test
    fun badFormatRejected() = runTest {
        val e = RulesEngine("ex.manga", mangaHost())
        assertFailsWith<SourceBridgeException> {
            e.load(mangaRules().replace("\"rules/v1\"", "\"js/v1\""))
        }
    }

    @Test
    fun managerDispatchesRulesWithoutJsEngine() = runTest {
        val host = mangaHost()
        val manager = SourceRuntimeManager(
            engines = object : PluginEngineFactory {
                override fun create(spec: PluginRuntimeSpec, host: HostApi): SourcePluginEngine =
                    error("rules/v1 不应走 JS 引擎工厂")
            },
            hosts = PluginHostProvider { host },
        )
        val spec = PluginRuntimeSpec(
            key = "ex.manga",
            script = mangaRules(),
            domains = setOf("cdn.example.com"),
            apiVersion = 4,
            appVersion = "1.0.0",
            scriptSha256 = "rules-sha",
            format = "rules/v1",
        )
        try {
            val first = manager.acquire(spec)
            assertTrue(first.has(SourceCapability.SEARCH))
            assertTrue(first.searchWorks("T", "[]", 1).contains("w1"))
            // 同签名命中缓存，不重建。
            assertTrue(manager.acquire(spec) === first)
            assertEquals(listOf("ex.manga"), manager.cachedKeys())
            // 形态参与签名：同脚本不同形态不共享运行时。
            assertTrue(spec.signature() != spec.copy(format = "js/v1").signature())
            manager.invalidate("ex.manga")
            assertEquals(emptyList(), manager.cachedKeys())
        } finally {
            manager.close()
        }
    }

    @Test
    fun settingsFallbackChainAndTemplate() = runTest {
        val urls = mutableListOf<String>()
        val cookies = CookieStore()
        val mock = MockEngine { request ->
            urls += request.url.toString()
            respond(
                content = ByteReadChannel(searchHtml),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType to listOf("text/html")),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val network: HostNetwork = KtorHostNetwork(http, cookies, setOf("a.example", "b.example")) { 0L }
        val host = object : HostApi {
            override val network: HostNetwork = network
            override val html: HostHtml = KsoupHostHtml()
            override val convert: HostConvert = CryptoHostConvert()
            override val ui: HostUi = NoopHostUi()
            override val utils: HostUtils = HostUtilsImpl()
            override val storage: HostStorage = InMemoryHostStorage()
            override val novel: HostNovel = NovelHost()
        }
        val pkg = """
        {
          "format": "rules/v1", "key": "ex.settings", "name": "S", "version": "1.0.0",
          "kind": ["manga"], "domains": ["a.example", "b.example"], "baseUrl": "https://cdn.example.com/",
          "settings": {"api": {"title": "线路", "type": "select",
            "options": [{"value": "https://a.example", "text": "A"}, {"value": "https://b.example", "text": "B"}],
            "default": "https://a.example"}},
          "search": {
            "request": {"method": "GET", "url": "@setting:api/s?q=@keyword&page=@page"},
            "mode": "html", "list": ".book-li",
            "fields": {"id": "a@href | fileName", "title": "a@text | trim"},
            "pagination": {"maxPage": "1"}
          },
          "detail": {
            "request": {"url": "/d"}, "mode": "html",
            "title": "h1@text",
            "chapters": {"list": "#chapters a", "id": "@href | fileName", "title": "@text | trim"}
          },
          "content": {"images": {"list": "img", "value": "@src | absUrl"}}
        }
        """.trimIndent()
        val e = RulesEngine("ex.settings", host)
        e.load(pkg)
        // describe 透出设置声明（含 default），宿主回退链可用。
        val d = SourceDescriptor.fromJson(e.describe(), emptySet(), 4)
        assertEquals("https://a.example", d.setting("api")?.default)
        // 缺省走 default。
        e.applySettings("{}")
        e.searchWorks("T", "[]", 1)
        assertTrue(urls.last().startsWith("https://a.example/s"), urls.last())
        // 用户值覆盖 default。
        e.applySettings("""{"api":"https://b.example"}""")
        e.searchWorks("T", "[]", 1)
        assertTrue(urls.last().startsWith("https://b.example/s"), urls.last())
        // SourceRuntime 回退链同样生效（descriptor 默认 + 用户值合并后预热）。
        val runtime = SourceRuntime(d, e)
        runtime.updateSettings("""{"api":"https://b.example"}""")
        assertEquals("https://b.example", host.storage.get("ex.settings", "setting:api"))
    }

    @Test
    fun diagnoseExposesThreeSteps() = runTest {
        val e = RulesEngine("ex.manga", mangaHost())
        e.load(mangaRules())
        val d = Json.parseToJsonElement(e.diagnose("T", 2)).jsonObject
        assertEquals("https://cdn.example.com/s?q=T&page=2", d["requestUrl"]?.jsonPrimitive?.contentOrNull)
        assertEquals(200, d["status"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertTrue((d["rawLength"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0) > 0)
        assertEquals(".book-li", d["listSelector"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, d["matched"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(2, d["kept"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(5, d["maxPage"]?.jsonPrimitive?.contentOrNull?.toInt())
        val sample = d["sample"]!!.jsonArray
        assertEquals("w1", sample[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
        // 失败走 error 通道，不抛异常（面板可展示）。
        val bad = RulesEngine("ex.manga", host { HttpStatusCode.NotFound to "" })
        bad.load(mangaRules())
        val err = Json.parseToJsonElement(bad.diagnose("T", 1)).jsonObject
        assertEquals("CONTRACT", err["code"]?.jsonPrimitive?.contentOrNull)

        // 第二步：目录诊断。
        val dd = Json.parseToJsonElement(e.diagnoseDetail("w1")).jsonObject
        assertEquals("https://cdn.example.com/comic/w1", dd["requestUrl"]?.jsonPrimitive?.contentOrNull)
        assertEquals("W1", dd["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, dd["matched"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(2, dd["kept"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals("c1", dd["sample"]!!.jsonArray[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
        val derr = Json.parseToJsonElement(bad.diagnoseDetail("w1")).jsonObject
        assertEquals("CONTRACT", derr["code"]?.jsonPrimitive?.contentOrNull)

        // 第三步：内容诊断（图片）。
        val dc = Json.parseToJsonElement(e.diagnoseContent("w1", "/comic/w1/c1.html")).jsonObject
        assertEquals("images", dc["mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals(2, dc["matched"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals(2, dc["kept"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertTrue(dc["sample"]!!.jsonArray[0].jsonPrimitive.content.contains("p1.jpg"))
        val cerr = Json.parseToJsonElement(bad.diagnoseContent("w1", "c1")).jsonObject
        assertEquals("CONTRACT", cerr["code"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun diagnoseContentText() = runTest {
        val h = host { p ->
            when {
                p.startsWith("/s") -> HttpStatusCode.OK to searchHtml
                p == "/n/n1" -> HttpStatusCode.OK to detailHtml
                p == "/n/n1/ch1" -> HttpStatusCode.OK to novelHtml
                else -> HttpStatusCode.NotFound to ""
            }
        }
        val e = RulesEngine("ex.novel", h)
        e.load(novelRules())
        val dc = Json.parseToJsonElement(e.diagnoseContent("n1", "/n/n1/ch1")).jsonObject
        assertEquals("text", dc["mode"]?.jsonPrimitive?.contentOrNull)
        assertEquals("plain", dc["format"]?.jsonPrimitive?.contentOrNull)
        assertTrue((dc["length"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0) > 0)
        assertTrue(dc["excerpt"]?.jsonPrimitive?.contentOrNull.orEmpty().contains("第一段"))
    }
}
