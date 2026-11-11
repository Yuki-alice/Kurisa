package data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CookieStoreTest {

    private fun store(nowMs: Long = 1_760_000_000_000L): CookieStore {
        val s = CookieStore()
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0"
        s.put(seedCookie("example.net", "cf_clearance", "v1", nowMs, ua))
        s.put(seedCookie("example.net", "session", "s1", nowMs))
        s.put(seedCookie(".example.net", "wide", "w1", nowMs))
        return s
    }

    @Test
    fun hostOnlyDoesNotMatchSubdomain() {
        val s = store()
        val now = 1_760_000_000_000L
        assertTrue(s.headerFor("https://example.net/a", now)?.contains("cf_clearance") == true)
        assertTrue(s.matching("https://cdn.example.net/a", now).none { it.name == "session" })
        assertTrue(s.matching("https://cdn.example.net/a", now).any { it.name == "wide" })
    }

    @Test
    fun differentSiteIsolation() {
        val s = store()
        val now = 1_760_000_000_000L
        assertTrue(s.matching("https://api.example.com/x", now).isEmpty())
    }

    @Test
    fun pairedUserAgentRoundTrip() {
        val s = store()
        val now = 1_760_000_000_000L
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0"
        assertEquals(ua, s.pairedUserAgentFor("https://example.net/x", now))
        assertNull(s.matching("https://example.net/x", now).first { it.name == "session" }.pairedUserAgent)
    }

    @Test
    fun expiredExcluded() {
        val now = 1_760_000_000_000L
        val s = CookieStore()
        s.put(
            seedCookie("a.example", "k", "v", now - 1000).copy(expiresAtEpochMs = now - 1),
        )
        assertNull(s.headerFor("https://a.example/", now))
    }

    @Test
    fun reChallengeOnlyInvalidatesClearance() {
        val s = store()
        val now = 1_760_000_000_000L
        val n = s.markInvalid("example.net", "re-challenged", CookieStore.CF_COOKIES)
        assertEquals(1, n)
        assertTrue(s.matching("https://example.net/a", now).none { it.name == "cf_clearance" })
        assertTrue(s.headerFor("https://example.net/a", now)?.contains("session") == true)
    }

    @Test
    fun hostAndPathHelpers() {
        assertEquals("example.net", CookieStore.hostOf("https://example.net/cn/comics?q=1"))
        assertEquals("/", CookieStore.pathOf("https://a.example"))
        assertEquals("/cn/comics", CookieStore.pathOf("https://example.net/cn/comics?q=1"))
        assertTrue(CookieStore.hostMatches("cdn.example.net", ".example.net"))
        assertFalse(CookieStore.hostMatches("cdn.example.net", "example.net"))
        assertTrue(CookieStore.pathMatches("/a/b", "/a"))
    }
}
