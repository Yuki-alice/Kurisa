package source.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelNormalizeTest {

    @Test
    fun absUrlResolves() {
        assertEquals("https://a.example/x", ModelNormalize.absUrl("https://a.example/", "/x"))
        assertEquals("https://a.example/x/y", ModelNormalize.absUrl("https://a.example/", "x/y"))
        assertEquals("https://c.example/z", ModelNormalize.absUrl("https://a.example/", "//c.example/z"))
        assertEquals("https://a.example/x", ModelNormalize.absUrl("https://a.example", "https://a.example/x"))
        assertEquals("", ModelNormalize.absUrl("https://a.example/", "  "))
    }

    @Test
    fun fileNameStrips() {
        assertEquals("w1", ModelNormalize.fileName("/comic/w1.html"))
        assertEquals("c1", ModelNormalize.fileName("https://cdn.example.com/w1/c1.html?x=1"))
        assertEquals("w1", ModelNormalize.fileName("/comic/w1/"))
        assertEquals("", ModelNormalize.fileName(""))
    }

    @Test
    fun titleFallback() {
        assertEquals("T x", ModelNormalize.titleOr("  T\n  x ", "F"))
        assertEquals("F", ModelNormalize.titleOr("   ", "F"))
    }

    @Test
    fun chapterNumberParses() {
        assertEquals(12, ModelNormalize.chapterNumber("第12话"))
        assertEquals(3, ModelNormalize.chapterNumber("Chapter 3"))
        assertEquals(7, ModelNormalize.chapterNumber("Ep07"))
        assertEquals(101, ModelNormalize.chapterNumber("第一卷 101"))
        assertNull(ModelNormalize.chapterNumber("序章"))
    }

    @Test
    fun dedupeKeepsFirst() {
        data class W(val id: String, val t: String)
        val out = ModelNormalize.dedupeById(
            listOf(W("a", "1"), W("", "x"), W("a", "2"), W("b", "3")),
        ) { it.id }
        assertEquals(listOf("a", "b"), out.map { it.id })
        assertEquals("1", out.first().t)
    }

    @Test
    fun kindFallback() {
        assertEquals(ContentKind.MANGA, ModelNormalize.normalizeKind("manga", ContentKind.NOVEL))
        assertEquals(ContentKind.NOVEL, ModelNormalize.normalizeKind("??", ContentKind.NOVEL))
        assertEquals(ContentKind.MANGA, ModelNormalize.normalizeKind(null, ContentKind.MANGA))
        assertTrue(ContentKind.of("video") == ContentKind.VIDEO)
    }
}
