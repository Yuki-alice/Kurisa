package data.network

/** Cloudflare 拦截类型（三类处置完全不同，不可混为一谈）。 */
enum class ChallengeKind { NONE, MANAGED_CHALLENGE, WAF_BLOCK, RATE_LIMITED, CF_OUTAGE }

data class Verdict(
    val kind: ChallengeKind,
    val needsBrowser: Boolean,
    val retryable: Boolean,
    val hint: String,
)

/**
 * Cloudflare 拦截识别（M0 CloudflareGuard 产品化）。
 * 关键判据为响应头 `cf-mitigated: challenge`，body 仅兜底。
 */
object CloudflareGuard {
    fun classify(status: Int, headers: Map<String, String>, body: String): Verdict {
        val h = headers.entries.associate { it.key.lowercase() to it.value }
        val mitigated = h["cf-mitigated"]?.lowercase()
        val sample = body.take(20_000)

        if (status == 429) {
            return Verdict(
                ChallengeKind.RATE_LIMITED, needsBrowser = false, retryable = true,
                hint = "请求过于频繁，已触发限流，稍后重试即可（不要重置 Cookie）。",
            )
        }

        if (status >= 400) {
            val isBlock = sample.contains("Sorry, you have been blocked", true) ||
                Regex("""error code:\s*1(020|012|009|015)""").containsMatchIn(sample)
            if (isBlock) {
                return Verdict(
                    ChallengeKind.WAF_BLOCK, needsBrowser = false, retryable = false,
                    hint = "站点按访问来源直接拒绝（IP 声誉或地区限制），换网络后重试；重复请求会加重封禁。",
                )
            }
            val isChallenge = mitigated == "challenge" ||
                sample.contains("Just a moment", true) ||
                sample.contains("Checking your browser", true) ||
                sample.contains("challenge-platform", true) ||
                sample.contains("cf-chl", true)
            if (isChallenge) {
                return Verdict(
                    ChallengeKind.MANAGED_CHALLENGE, needsBrowser = true, retryable = false,
                    hint = "站点要求浏览器验证，需要在真实浏览器中完成一次人工验证。",
                )
            }
        }

        if (status in 500..599 && (h["server"]?.contains("cloudflare", true) == true || h["cf-ray"] != null)) {
            return Verdict(
                ChallengeKind.CF_OUTAGE, needsBrowser = false, retryable = true,
                hint = "Cloudflare 边缘节点异常，稍后重试。",
            )
        }

        return Verdict(ChallengeKind.NONE, needsBrowser = false, retryable = false, hint = "")
    }
}
