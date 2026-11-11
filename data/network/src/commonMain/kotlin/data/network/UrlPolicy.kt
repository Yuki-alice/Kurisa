package data.network

/**
 * Ktor 白名单 / 重定向一致性：三端同一套规则（M0 UrlPolicy 的产品化，语义冻结）。
 * 规则（对齐架构 4.2 domains + C2）：
 * 1. 只允许 https，host 必须命中源声明 domains（后缀匹配，大小写不敏感）；
 * 2. 重定向必须重新过白名单，且禁止 https→http 降级；
 * 3. 调用方最多跟 5 跳，超限即拒（SourceHttpClient 强制）。
 *
 * security-audit MASVS-NETWORK：N1 cleartext 禁止（此处强制 https）、
 * N5 敏感数据不进 URL（调用方审计）、N3 无 TrustManager 旁路（用引擎默认 TLS）。
 */
object UrlPolicy {
    const val MAX_HOPS = 5

    fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()

    fun schemeOf(url: String): String = url.substringBefore("://", "").lowercase()

    fun isAllowed(url: String, domains: Set<String>): Boolean {
        if (schemeOf(url) != "https") return false
        val host = hostOf(url)
        if (host.isEmpty()) return false
        return domains.any { d ->
            val dd = d.lowercase().removePrefix(".")
            host == dd || host.endsWith(".$dd")
        }
    }

    data class RedirectVerdict(val ok: Boolean, val reason: String)

    fun validateRedirect(current: String, location: String, domains: Set<String>): RedirectVerdict {
        if (location.isEmpty()) return RedirectVerdict(false, "empty-location")
        val next = if (location.startsWith("/")) {
            val scheme = schemeOf(current)
            "$scheme://${hostOf(current)}$location"
        } else if (!location.contains("://")) {
            return RedirectVerdict(false, "bad-location")
        } else location
        if (schemeOf(current) == "https" && schemeOf(next) == "http") {
            return RedirectVerdict(false, "downgrade-blocked")
        }
        if (!isAllowed(next, domains)) return RedirectVerdict(false, "domain-blocked:$next")
        return RedirectVerdict(true, next)
    }
}
