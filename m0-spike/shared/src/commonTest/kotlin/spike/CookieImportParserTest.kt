package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 粘贴解析器三端一致：5 种用户复制格式。
 */
class CookieImportParserTest {

    private val now = 1_760_000_000_000L
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0"

    @Test
    fun cookieHeader() {
        val r = CookieImportParser.parse("cf_clearance=abc123; __cf_bm=def456", "mycomic.com", now, ua)
        assertEquals(2, r.cookies.size)
        assertEquals("cookie-header", r.format)
        assertTrue(r.cookies.all { it.pairedUserAgent == ua })
    }

    @Test
    fun devtoolsTable() {
        val raw = "cf_clearance\tabc123\t.mycomic.com\t/\n__cf_bm\tdef456\t.mycomic.com\t/"
        val r = CookieImportParser.parse(raw, "mycomic.com", now, ua)
        assertEquals(2, r.cookies.size)
        assertEquals("tab-separated", r.format)
    }

    @Test
    fun multiLine() {
        val r = CookieImportParser.parse("cf_clearance=abc123\n__cf_bm=def456", "mycomic.com", now, ua)
        assertEquals(2, r.cookies.size)
    }

    @Test
    fun jsonArray() {
        val r = CookieImportParser.parse("""[{"name":"cf_clearance","value":"abc123"}]""", "mycomic.com", now, ua)
        assertEquals(1, r.cookies.size)
        assertEquals("json", r.format)
    }

    @Test
    fun dirtyInput() {
        val r = CookieImportParser.parse("  cf_clearance = abc ; ;  ", "mycomic.com", now, ua)
        assertEquals(1, r.cookies.size)
        assertEquals("cf_clearance", r.cookies[0].name)
    }

    @Test
    fun illegalNamesFiltered() {
        val r = CookieImportParser.parse("a=1; bad;name=2; b=3", "x.com", now, null)
        // "bad" 无 = 被丢弃，"bad;name" 含非法字符被过滤
        assertTrue(r.cookies.any { it.name == "a" })
        assertTrue(r.cookies.any { it.name == "b" })
    }
}
