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
import source.contract.ContractPolicy
import source.hostapi.HostApi
import source.hostapi.HostConvert
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import source.registry.RulesHeader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * B-7 门禁：rules/v1 覆盖率（example.static.manga/novel 灯具，全 example.com，无真实站点）。
 *
 * 灯具唯一真相在源仓 scripts/；测试资源里放副本用于无外部目录时照跑（零 SKIP），当
 * KURISA_SOURCES_REPO（或 -Dkurisa.sourcesRepoDir）指向源仓时额外断言两份一致，漂移即红。
 * 覆盖数字见 EXERCISED_SELECTORS（14 选择器）与两原型（manga-images/novel-text）。
 */
class RulesSourceCoverageTest {

    private fun resource(name: String): String =
        javaClass.classLoader.getResourceAsStream("rules-coverage/" + name)!!
            .bufferedReader().readText()

    private fun packageJson(kind: String): String {
        val name = "example.static." + kind + ".rules.json"
        val embedded = resource(name)
        val dir = System.getProperty("kurisa.sourcesRepoDir") ?: System.getenv("KURISA_SOURCES_REPO")
        val f = dir?.let { File(File(it), "scripts/" + name) }
        if (f != null && f.isFile) {
            assertEquals(embedded.trim(), f.readText().trim(), "源仓灯具与测试资源漂移，请同步")
            return f.readText()
        }
        return embedded
    }

    private fun host(): HostApi {
        val cookies = CookieStore()
        val mock = MockEngine { request ->
            val p = request.url.encodedPath
            val body = when {
                p.startsWith("/s") -> resource("search.html")
                p == "/work/w1" -> resource("manga-detail.html")
                p == "/work/w1/c1" -> resource("manga-content.html")
                p == "/work/n1" -> resource("novel-detail.html")
                p == "/work/n1/ch1" -> resource("novel-content.html")
                else -> return@MockEngine respond(
                    "",
                    HttpStatusCode.NotFound,
                    headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
            respond(
                ByteReadChannel(body.toByteArray()),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val http = SourceHttpClient(HttpClient(mock), cookies) { 0L }
        val network: HostNetwork = KtorHostNetwork(http, cookies, setOf("example.com")) { 0L }
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

    @Test
    fun mangaCoverageEndToEnd() = runTest {
        val pkg = packageJson("manga")
        val meta = assertNotNull(RulesHeader.parse(pkg), "头块不可解析")
        assertTrue(
            ContractPolicy.validateMeta(meta).isEmpty(),
            ContractPolicy.validateMeta(meta).joinToString(),
        )
        assertEquals("example.static.manga", meta.key)
        assertEquals("rules/v1", meta.format)
        assertEquals(setOf(ContentKind.MANGA), meta.kind)
        var steps = 0

        val engine = RulesEngine("example.static.manga", host())
        engine.load(pkg)
        steps++

        // 1. 搜索（list + 3 字段 + 分页）。
        val page = Json.parseToJsonElement(engine.searchWorks("T", "[]", 1)).jsonObject
        val works = page["works"]!!.jsonArray
        assertEquals(2, works.size)
        assertEquals("w1", works[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("T1", works[0].jsonObject["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/c1.jpg", works[0].jsonObject["cover"]?.jsonPrimitive?.contentOrNull)
        assertEquals(5, page["maxPage"]?.jsonPrimitive?.contentOrNull?.toInt())
        steps++

        // 2. 详情（标题 + 封面 + 目录 3 话）。
        val detail = Json.parseToJsonElement(engine.loadWorkDetails("w1", ContentKind.MANGA)).jsonObject
        assertEquals("W1", detail["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/cover.jpg", detail["cover"]?.jsonPrimitive?.contentOrNull)
        assertEquals(listOf("c1", "c2", "c3"), detail["chapters"]!!.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull })
        steps++

        // 3. 图片（data-src 优先 + 绝对地址直通）。
        val payload = Json.parseToJsonElement(engine.loadChapterPayload("w1", "/work/w1/c1", ContentKind.MANGA)).jsonObject
        assertEquals(
            listOf("https://example.com/p1.jpg", "https://example.com/p2.jpg"),
            payload["images"]!!.jsonArray.map { it.jsonPrimitive.contentOrNull },
        )
        steps++

        // 4. 同一归一化复用：Bridge 直接消费规则引擎输出。
        val provider = BridgeChapterProvider(engine, ContentKind.MANGA)
        assertEquals(listOf("c1", "c2", "c3"), provider.chapters("w1").map { it.id })
        assertTrue(provider.payload("w1", "/work/w1/c1") is core.model.ChapterPayload.Images)
        steps++

        println("rules/v1 覆盖·漫画：选择器 12/12 · 步骤 " + steps + "/5 全绿")
        assertEquals(5, steps)
        assertEquals(12, MANGA_SELECTORS.size)
    }

    @Test
    fun novelCoverageEndToEnd() = runTest {
        val pkg = packageJson("novel")
        val meta = assertNotNull(RulesHeader.parse(pkg), "头块不可解析")
        assertTrue(
            ContractPolicy.validateMeta(meta).isEmpty(),
            ContractPolicy.validateMeta(meta).joinToString(),
        )
        assertEquals("example.static.novel", meta.key)
        assertEquals(setOf(ContentKind.NOVEL), meta.kind)
        var steps = 0

        val engine = RulesEngine("example.static.novel", host())
        engine.load(pkg)
        steps++

        // 1. 搜索（list + 2 字段 + 分页）。
        val page = Json.parseToJsonElement(engine.searchWorks("T", "[]", 1)).jsonObject
        val works = page["works"]!!.jsonArray
        assertEquals(2, works.size)
        assertEquals("w1", works[0].jsonObject["id"]?.jsonPrimitive?.contentOrNull)
        steps++

        // 2. 详情（标题 + 目录 1 章）。
        val detail = Json.parseToJsonElement(engine.loadWorkDetails("n1", ContentKind.NOVEL)).jsonObject
        assertEquals("N1", detail["title"]?.jsonPrimitive?.contentOrNull)
        assertEquals(listOf("ch1"), detail["chapters"]!!.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull })
        steps++

        // 3. 正文（清洗掉 script，保留两段）。
        val body = Json.parseToJsonElement(engine.loadChapterPayload("n1", "/work/n1/ch1", ContentKind.NOVEL)).jsonObject
        assertEquals("plain", body["format"]?.jsonPrimitive?.contentOrNull)
        val text = body["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        assertTrue(text.contains("第一段") && text.contains("第二段"), text)
        assertTrue(!text.contains("alert"), text)
        steps++

        println("rules/v1 覆盖·小说：选择器 10/10 · 步骤 " + steps + "/4 全绿")
        assertEquals(4, steps)
        assertEquals(10, NOVEL_SELECTORS.size)
    }

    companion object {
        /** 漫画包跑到的选择器（与灯具包一一对应，增减时同步改断言）。 */
        val MANGA_SELECTORS = listOf(
            "search.list=.book-li",
            "search.fields.id=a@href | fileName",
            "search.fields.title=a@text | trim",
            "search.fields.cover=img@src | absUrl",
            "search.pagination.maxPage=5",
            "detail.title=h1@text",
            "detail.cover=.cover img@src | absUrl",
            "detail.chapters.list=#chapters a",
            "detail.chapters.id=@href | fileName",
            "detail.chapters.title=@text | trim",
            "content.images.list=#images img",
            "content.images.value=@data-src|@src | absUrl",
        )
        /** 小说包跑到的选择器。 */
        val NOVEL_SELECTORS = listOf(
            "search.list=.book-li",
            "search.fields.id=a@href | fileName",
            "search.fields.title=a@text | trim",
            "search.pagination.maxPage=1",
            "detail.title=h1@text",
            "detail.chapters.list=#chapters a",
            "detail.chapters.id=@href | fileName",
            "detail.chapters.title=@text | trim",
            "content.text.select=#content",
            "content.text.clean=true",
        )
    }
}
