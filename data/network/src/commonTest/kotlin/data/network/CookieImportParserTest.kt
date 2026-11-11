package data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CookieImportParserTest {

    @Test
    fun headerFormat() {
        val r = CookieImportParser.parse("a=1; b=2", "example.com", 1L, null)
        assertEquals(2, r.cookies.size)
        assertEquals("cookie-header", r.format)
    }

    @Test
    fun multiLineFormat() {
        val r = CookieImportParser.parse("cf_clearance=v1\nsession=s1", "example.com", 1L, "UA")
        assertEquals(2, r.cookies.size)
        assertEquals("UA", r.cookies.first { it.name == "cf_clearance" }.pairedUserAgent)
    }

    @Test
    fun jsonFormat() {
        val r = CookieImportParser.parse(
            """[{"name":"cf_clearance","value":"v1"}]""", "example.com", 1L, null
        )
        assertEquals("json", r.format)
        assertEquals(1, r.cookies.size)
    }

    @Test
    fun emptyWarns() {
        val r = CookieImportParser.parse("   ", "example.com", 1L, null)
        assertTrue(r.cookies.isEmpty())
        assertTrue(r.warnings.isNotEmpty())
    }
}
