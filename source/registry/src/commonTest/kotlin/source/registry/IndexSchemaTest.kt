package source.registry

import source.contract.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B-6 门禁：统一 schema v2 两种形状（主仓 apiVersion / 源仓 version）都兼容收口。 */
class IndexSchemaTest {

    @Test
    fun parsesMainRepoShapeWithNewFields() {
        val raw = """
            {"apiVersion":4,"sources":[{
              "name":"N","key":"com.example.static","filename":"example.static.rules.json",
              "version":"1.0.0","kind":["manga","novel","unknown"],"domains":["example.com"],
              "capabilities":["search"],
              "sha256":"b470315fe49154e61a16101b9e1d1e90f64bbecc9f00e7d7695b71a0",
              "format":"rules/v1"
            }]}""".trimIndent()
        val index = SourceIndexParser.parse(raw)
        assertEquals(4, index.version)
        val e = index.sources.single()
        assertEquals("com.example.static", e.key)
        assertEquals("rules/v1", e.format)
        assertEquals(
            "b470315fe49154e61a16101b9e1d1e90f64bbecc9f00e7d7695b71a0",
            e.sha256,
        )
        assertEquals(setOf(ContentKind.MANGA, ContentKind.NOVEL), e.kind)
        assertEquals(listOf("example.com"), e.domains)
        assertNull(e.apiVersion)
        assertNull(e.minApp)
    }

    @Test
    fun parsesSourcesRepoLegacyShape() {
        val raw = """
            {"version":1,"sources":[{
              "key":"com.example.old","name":"O","version":"0.9.0","apiVersion":2,
              "kind":["manga"],"domains":["example.org"],"minApp":"1.0.0"
            }]}""".trimIndent()
        val index = SourceIndexParser.parse(raw)
        assertEquals(1, index.version)
        val e = index.sources.single()
        assertEquals(2, e.apiVersion)
        assertEquals("1.0.0", e.minApp)
        assertEquals("js/v1", e.format)
        assertNull(e.sha256)
        assertEquals(setOf(ContentKind.MANGA), e.kind)
    }

    @Test
    fun skipsBadEntriesButKeepsGoodOnes() {
        val raw = """
            {"version":1,"sources":[
              {"key":"","version":"1.0.0"},
              {"key":"com.example.good","version":"1.0.0","kind":["nope"],"domains":[]}
            ]}""".trimIndent()
        val index = SourceIndexParser.parse(raw)
        assertEquals(1, index.sources.size)
        assertEquals("com.example.good", index.sources[0].key)
        assertTrue(index.sources[0].kind.isEmpty())
        assertTrue(index.sources[0].domains.isEmpty())
    }

    @Test
    fun emptySourcesIsValid() {
        val index = SourceIndexParser.parse("""{"version":1,"sources":[]}""")
        assertTrue(index.sources.isEmpty())
    }
}
