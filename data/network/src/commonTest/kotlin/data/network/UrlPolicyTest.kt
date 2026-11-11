package data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ktor 白名单 / 重定向：三端一致（AGENTS 硬规则：Ktor 改动必须同时跑本文件）。 */
class UrlPolicyTest {

    private val domains = setOf("mangadex.org", "api.mangadex.org", ".mycomic.com")

    @Test
    fun allowlistExactAndSubdomain() {
        assertTrue(UrlPolicy.isAllowed("https://api.mangadex.org/manga", domains))
        assertTrue(UrlPolicy.isAllowed("https://cdn.mycomic.com/x", domains))
        assertFalse(UrlPolicy.isAllowed("https://evil.com/x", domains))
        assertFalse(UrlPolicy.isAllowed("http://api.mangadex.org/x", domains))
        assertTrue(UrlPolicy.isAllowed("https://API.MANGADEX.ORG/x", domains))
    }

    @Test
    fun redirectRecheck() {
        val ok = UrlPolicy.validateRedirect(
            "https://api.mangadex.org/a", "https://api.mangadex.org/b", domains
        )
        assertTrue(ok.ok)
        val blocked = UrlPolicy.validateRedirect(
            "https://api.mangadex.org/a", "https://evil.com/b", domains
        )
        assertFalse(blocked.ok)
    }

    @Test
    fun downgradeBlocked() {
        val v = UrlPolicy.validateRedirect(
            "https://api.mangadex.org/a", "http://api.mangadex.org/b", domains
        )
        assertFalse(v.ok)
        assertEquals("downgrade-blocked", v.reason)
    }

    @Test
    fun relativeRedirect() {
        val v = UrlPolicy.validateRedirect("https://api.mangadex.org/a", "/b?page=2", domains)
        assertTrue(v.ok)
        assertEquals("https://api.mangadex.org/b?page=2", v.reason)
    }

    @Test
    fun maxHops() {
        var url = "https://api.mangadex.org/0"
        repeat(5) { i ->
            val v = UrlPolicy.validateRedirect(url, "https://api.mangadex.org/${i + 1}", domains)
            assertTrue(v.ok)
            url = v.reason
        }
        assertEquals("https://api.mangadex.org/5", url)
    }
}
