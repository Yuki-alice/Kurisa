package source.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelsTest {

    @Test
    fun workRoundTrip() {
        val w = Work(
            id = "b23c9bb1", title = "お前じゃん", subtitle = "Suzumoto Kou",
            cover = "https://example.com/c.jpg", tags = listOf("Romance"),
            language = "ja", kind = ContentKind.MANGA,
        )
        val raw = ContractJson.json.encodeToString(Work.serializer(), w)
        val back: Work = ContractJson.json.decodeFromString(raw)
        assertEquals(w, back)
    }

    @Test
    fun workDetailsChapters() {
        val d = WorkDetails(
            work = Work(id = "w1", title = "T", kind = ContentKind.NOVEL),
            chapters = listOf(Chapter("c1", "第1话", 0), Chapter("c2", "第2话", 1)),
        )
        val raw = ContractJson.json.encodeToString(WorkDetails.serializer(), d)
        val back: WorkDetails = ContractJson.json.decodeFromString(raw)
        assertEquals(2, back.chapters.size)
        assertEquals("c2", back.chapters[1].id)
    }

    @Test
    fun contentKindParsing() {
        assertEquals(ContentKind.MANGA, ContentKind.of("manga"))
        assertEquals(ContentKind.NOVEL, ContentKind.of(" NOVEL "))
        // v4 预留：video/audio 已为合法类型（渲染 deferred，调用侧显式 CONTRACT）。
        assertEquals(ContentKind.VIDEO, ContentKind.of("video"))
        assertEquals(ContentKind.AUDIO, ContentKind.of("audio"))
        assertEquals(null, ContentKind.of("podcast"))
    }

    @Test
    fun accountDeclHelpers() {
        val a = AccountDecl(webviewUrl = "https://accounts.example.com/login", cookieFields = listOf("cf_clearance"))
        assertTrue(a.hasWebview && a.hasCookieLogin)
        assertTrue(!AccountDecl().hasWebview)
    }

    @Test
    fun metaJsonIgnoresUnknown() {
        val raw = """{"name":"N","key":"com.example.n","version":"1.0.0","minAppVersion":"1.0.0",
            "apiVersion":2,"kind":["novel"],"domains":["example.com"],"capabilities":[],"futureField":1}"""
        val m = ContractJson.decodeMeta(raw)
        assertEquals("com.example.n", m.key)
        assertEquals(setOf(ContentKind.NOVEL), m.kind)
    }
}
