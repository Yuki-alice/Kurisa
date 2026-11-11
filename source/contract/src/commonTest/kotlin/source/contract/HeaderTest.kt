package source.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeaderTest {

    private val valid = """
        // ==SourceScript==
        // @name        示例小说源
        // @key         com.example.novel
        // @version     1.0.0
        // @apiVersion  2
        // @minApp      1.0.0
        // @kind        novel
        // @domains     example.com, cdn.example.com
        // @capabilities search, chapterText
        // ==/SourceScript==
        class ExampleNovel extends ComicSource {}
    """.trimIndent()

    @Test
    fun parsesFullHeader() {
        val m = SourceScriptHeader.parse(valid)
        assertTrue(m != null)
        m as SourceMeta
        assertEquals("com.example.novel", m.key)
        assertEquals("1.0.0", m.version)
        assertEquals(2, m.apiVersion)
        assertEquals(setOf(ContentKind.NOVEL), m.kind)
        assertEquals(listOf("example.com", "cdn.example.com"), m.domains)
        assertEquals(listOf("search", "chapterText"), m.capabilities)
        assertTrue(ContractPolicy.validateMeta(m).isEmpty())
    }

    @Test
    fun missingBlockIsNull() {
        assertNull(SourceScriptHeader.parse("class X extends ComicSource {}"))
    }

    @Test
    fun missingRequiredFieldIsNull() {
        val noKey = valid.lines().filterNot { it.contains("@key") }.joinToString("\n")
        assertNull(SourceScriptHeader.parse(noKey))
    }

    @Test
    fun badApiVersionIsNull() {
        assertNull(SourceScriptHeader.parse(valid.replace("@apiVersion  2", "@apiVersion  x")))
    }

    @Test
    fun multiKindAndDedup() {
        val raw = valid.replace("@kind        novel", "@kind        manga, novel, novel")
            .replace("@domains     example.com, cdn.example.com", "@domains     example.com, example.com")
        val m = SourceScriptHeader.parse(raw)
        assertTrue(m != null)
        m as SourceMeta
        assertEquals(setOf(ContentKind.MANGA, ContentKind.NOVEL), m.kind)
        assertEquals(listOf("example.com"), m.domains)
    }
}
