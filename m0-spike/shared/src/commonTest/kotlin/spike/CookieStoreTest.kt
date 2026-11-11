package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * M0 Gate ①/② 前置：Cookie 存储语义三端一致。
 * 纯 Kotlin，无平台依赖，应在 android / ios / jvm 跑出完全一致结果。
 */
class CookieStoreTest {

    private fun store(nowMs: Long = 1_760_000_000_000L): CookieStore {
        val s = CookieStore()
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0"
        s.put(StoredCookie("mycomic.com", "cf_clearance", "v1", pairedUserAgent = ua, obtainedAtEpochMs = nowMs))
        s.put(StoredCookie("mycomic.com", "session", "s1", obtainedAtEpochMs = nowMs))
        s.put(StoredCookie(".mycomic.com", "wide", "w1", obtainedAtEpochMs = nowMs))
        return s
    }

    @Test
    fun hostOnlyDoesNotMatchSubdomain() {
        val s = store()
        val now = 1_760_000_000_000L
        assertTrue(s.headerFor("https://mycomic.com/a", now)?.contains("cf_clearance") == true)
        // 不带点的域要求精确匹配（host-only）
        assertTrue(s.matching("https://cdn.mycomic.com/a", now).none { it.name == "session" })
        // 带点前缀允许子域
        assertTrue(s.matching("https://cdn.mycomic.com/a", now).any { it.name == "wide" })
    }

    @Test
    fun differentSiteIsolation() {
        val s = store()
        val now = 1_760_000_000_000L
        assertTrue(s.matching("https://api.mangadex.org/x", now).isEmpty())
    }

    @Test
    fun pairedUserAgentRoundTrip() {
        val s = store()
        val now = 1_760_000_000_000L
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0"
        assertEquals(ua, s.pairedUserAgentFor("https://mycomic.com/x", now))
        assertNull(s.matching("https://mycomic.com/x", now).first { it.name == "session" }.pairedUserAgent)
    }

    @Test
    fun expiredExcluded() {
        val now = 1_760_000_000_000L
        val s = CookieStore()
        s.put(StoredCookie("a.com", "k", "v", expiresAtEpochMs = now - 1, obtainedAtEpochMs = now - 1000))
        assertNull(s.headerFor("https://a.com/", now))
    }

    @Test
    fun reChallengeOnlyInvalidatesClearance() {
        val s = store()
        val now = 1_760_000_000_000L
        val n = s.markInvalid("mycomic.com", "re-challenged", CookieStore.CF_COOKIES)
        assertEquals(1, n)
        assertTrue(s.matching("https://mycomic.com/a", now).none { it.name == "cf_clearance" })
        assertTrue(s.headerFor("https://mycomic.com/a", now)?.contains("session") == true)
    }

    @Test
    fun hostAndPathHelpers() {
        assertEquals("mycomic.com", CookieStore.hostOf("https://mycomic.com/cn/comics?q=1"))
        assertEquals("/", CookieStore.pathOf("https://a.com"))
        assertEquals("/cn/comics", CookieStore.pathOf("https://mycomic.com/cn/comics?q=1"))
        assertTrue(CookieStore.hostMatches("cdn.mycomic.com", ".mycomic.com"))
        assertFalse(CookieStore.hostMatches("cdn.mycomic.com", "mycomic.com"))
        assertTrue(CookieStore.pathMatches("/a/b", "/a"))
    }
}
