package data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CloudflareGuardTest {

    @Test
    fun managedChallengeByHeader() {
        val v = CloudflareGuard.classify(
            403, mapOf("cf-mitigated" to "challenge"), "<html>ok</html>"
        )
        assertEquals(ChallengeKind.MANAGED_CHALLENGE, v.kind)
        assertTrue(v.needsBrowser)
    }

    @Test
    fun wafBlock() {
        val v = CloudflareGuard.classify(
            403, emptyMap(), "<html>Sorry, you have been blocked<br>error code: 1020</html>"
        )
        assertEquals(ChallengeKind.WAF_BLOCK, v.kind)
    }

    @Test
    fun rateLimited() {
        val v = CloudflareGuard.classify(429, emptyMap(), "")
        assertEquals(ChallengeKind.RATE_LIMITED, v.kind)
        assertTrue(v.retryable)
    }

    @Test
    fun noneForNormal() {
        val v = CloudflareGuard.classify(200, emptyMap(), """{"ok":true}""")
        assertEquals(ChallengeKind.NONE, v.kind)
    }
}
