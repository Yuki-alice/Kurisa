package data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ktor 白名单 / 重定向：三端一致（AGENTS 硬规则：Ktor 改动必须同时跑本文件）。 */
class UrlPolicyTest {

    private val domains = setOf("example.com", "api.example.com", ".example.net")

    @Test
    fun allowlistExactAndSubdomain() {
        assertTrue(UrlPolicy.isAllowed("https://api.example.com/manga", domains))
        assertTrue(UrlPolicy.isAllowed("https://cdn.example.net/x", domains))
        assertFalse(UrlPolicy.isAllowed("https://evil.com/x", domains))
        assertFalse(UrlPolicy.isAllowed("http://api.example.com/x", domains))
        assertTrue(UrlPolicy.isAllowed("https://API.EXAMPLE.COM/x", domains))
    }

    @Test
    fun redirectRecheck() {
        val ok = UrlPolicy.validateRedirect(
            "https://api.example.com/a", "https://api.example.com/b", domains
        )
        assertTrue(ok.ok)
        val blocked = UrlPolicy.validateRedirect(
            "https://api.example.com/a", "https://evil.com/b", domains
        )
        assertFalse(blocked.ok)
    }

    @Test
    fun downgradeBlocked() {
        val v = UrlPolicy.validateRedirect(
            "https://api.example.com/a", "http://api.example.com/b", domains
        )
        assertFalse(v.ok)
        assertEquals("downgrade-blocked", v.reason)
    }

    @Test
    fun relativeRedirect() {
        val v = UrlPolicy.validateRedirect("https://api.example.com/a", "/b?page=2", domains)
        assertTrue(v.ok)
        assertEquals("https://api.example.com/b?page=2", v.reason)
    }

    @Test
    fun maxHops() {
        var url = "https://api.example.com/0"
        repeat(5) { i ->
            val v = UrlPolicy.validateRedirect(url, "https://api.example.com/${i + 1}", domains)
            assertTrue(v.ok)
            url = v.reason
        }
        assertEquals("https://api.example.com/5", url)
    }
}
