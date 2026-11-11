package spike

/**
 * Ktor 白名单 / 重定向一致性：三端同一套规则。
 * 规则（对齐 architecture 4.2 domains + C2）：
 * 1. 只允许 https，host 必须命中源声明 domains（后缀匹配，大小写不敏感）；
 * 2. 重定向（301/302/307/308）必须重新过白名单，且禁止 https→http 降级；
 * 3. 最多跟 5 跳，超限即拒。
 */
object UrlPolicy {
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
        // 相对路径：继承当前 scheme+host
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
