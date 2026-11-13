package app.shared

import data.network.CookieImportParser
import data.network.CookieStore
import data.network.UrlPolicy
import source.hostapi.HostLoginResult

/**
 * Cookie 导入的统一执行逻辑（BE-C3）。
 *
 * 两条入口共用同一套规则，避免「HostUi 兜底」与「用户主动导入」行为分叉：
 * 1. 域名必须是当前启用源声明 domains 之一（[UrlPolicy]），杜绝把别的站点 Cookie 交进来；
 * 2. `cf_clearance` / `__cf_bm` 必须与浏览器 UA 成对导入，否则整次拒绝；
 * 3. 解析不出合法 Cookie 一律不写 [CookieStore]；明文只在内存里经解析器流转。
 *
 * 本类不发起任何网络请求，也不接触平台 UI；[CookiePaste] 由调用方采集。
 */
class CookieImporter(
    private val cookies: CookieStore,
    private val allowedDomains: () -> Set<String>,
    private val nowMs: () -> Long = { 0L },
) {
    fun import(domain: String, paste: CookiePaste): HostLoginResult {
        val host = normalizeHost(domain)
        if (host.isEmpty() || !UrlPolicy.isAllowed("https://$host/", allowedDomains())) {
            return HostLoginResult.Cancelled("domain-not-allowed")
        }
        val parsed = CookieImportParser.parse(paste.raw, host, nowMs(), paste.userAgent)
        if (parsed.cookies.isEmpty()) {
            return HostLoginResult.Cancelled("no-valid-cookie")
        }
        val hasClearance = parsed.cookies.any { it.name in CookieStore.CF_COOKIES }
        if (hasClearance && paste.userAgent.isNullOrBlank()) {
            return HostLoginResult.Cancelled("paired-user-agent-required")
        }
        cookies.putAll(parsed.cookies)
        return HostLoginResult.Imported(parsed.cookies.size, parsed.format)
    }

    /** 允许粘贴完整 URL 或裸域名；只取 host，忽略 path / query / 端口。 */
    private fun normalizeHost(raw: String): String = raw.trim()
        .substringAfter("://", raw.trim())
        .substringBefore('/')
        .substringBefore(':')
        .removePrefix(".")
        .lowercase()
}
