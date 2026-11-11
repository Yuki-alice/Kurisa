package spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Cloudflare 三类拦截三端一致（Gate ① 契约层 + ADR-009 落地）。
 * 不依赖真实网络，用最小 fixture 断言分类器行为一致。
 */
class CloudflareGuardTest {

    @Test
    fun rateLimited() {
        val v = CloudflareGuard.classify(429, emptyMap(), "")
        assertEquals(ChallengeKind.RATE_LIMITED, v.kind)
        assertFalse(v.needsBrowser)
        assertTrue(v.retryable)
    }

    @Test
    fun normalPage() {
        val v = CloudflareGuard.classify(200, mapOf("server" to "cloudflare"), "<html>ok</html>")
        assertEquals(ChallengeKind.NONE, v.kind)
    }

    @Test
    fun mitigatedHeaderAloneIsChallenge() {
        val v = CloudflareGuard.classify(403, mapOf("cf-mitigated" to "challenge"), "")
        assertEquals(ChallengeKind.MANAGED_CHALLENGE, v.kind)
        assertTrue(v.needsBrowser)
    }

    @Test
    fun wafBlockPriorityOverChallenge() {
        // 阻断页里也可能出现 challenge-platform 字样，必须先判 WAF
        val body = "Sorry, you have been blocked <div>challenge-platform</div> error code: 1020"
        val v = CloudflareGuard.classify(403, mapOf("server" to "cloudflare"), body)
        assertEquals(ChallengeKind.WAF_BLOCK, v.kind)
        assertFalse(v.needsBrowser)
        assertFalse(v.retryable)
    }

    @Test
    fun challengeTextFallback() {
        val v = CloudflareGuard.classify(
            403, emptyMap(),
            "<html><title>Just a moment...</title>Checking your browser</html>"
        )
        assertEquals(ChallengeKind.MANAGED_CHALLENGE, v.kind)
    }

    @Test
    fun cfOutage() {
        val v = CloudflareGuard.classify(
            503, mapOf("server" to "cloudflare", "cf-ray" to "abc"),
            "edge error"
        )
        assertEquals(ChallengeKind.CF_OUTAGE, v.kind)
        assertTrue(v.retryable)
    }
}
