package app.shared

import data.network.CookieStore
import data.network.UrlPolicy
import source.hostapi.HostLoginResult
import source.hostapi.HostUi

/** 一次粘贴的内容：Cookie 整串 + 与之配对的浏览器 UA（架构红线，缺一不可）。 */
data class CookiePaste(val raw: String, val userAgent: String?)

/**
 * 桌面端登录兜底（BE-C3，ADR-009）：不内嵌浏览器，改由用户从 DevTools 粘贴 Cookie。
 *
 * 安全边界（security-audit MASVS-NETWORK / PLATFORM）：
 * - [allowedDomains] 是该源在运行时绑定的声明域名，登录 URL 与外链都必须过 `UrlPolicy`；
 *   恶意源无法借登录链路诱导用户把别的站点的 Cookie 交出来，也不引入任意 URL 网络出口。
 * - `cf_clearance` / `__cf_bm` 必须与 UA 成对导入，否则整次导入拒绝、不写 CookieJar；
 *   网络出口会用 `pairedUserAgentFor` 覆盖请求 UA，UA 不匹配时 Cloudflare 仍会拒绝。
 * - 本类只写 [CookieStore]，不读明文文件、不发起请求；明文只经 [CookieImportParser] 内存解析。
 */
class CookieImportHostUi(
    private val cookies: CookieStore,
    private val allowedDomains: Set<String>,
    private val requestPaste: suspend (domain: String) -> CookiePaste?,
    private val nowMs: () -> Long = { 0L },
    private val openExternal: (String) -> Unit = {},
) : HostUi {

    override suspend fun showMessage(text: String) = Unit

    override suspend fun launchUrl(url: String) {
        if (!UrlPolicy.isAllowed(url, allowedDomains)) {
            throw UnsupportedOperationException("domain-blocked:$url")
        }
        openExternal(url)
    }

    override suspend fun requestLogin(url: String): HostLoginResult {
        if (!UrlPolicy.isAllowed(url, allowedDomains)) {
            return HostLoginResult.Cancelled("domain-not-allowed")
        }
        val domain = UrlPolicy.hostOf(url)
        val paste = requestPaste(domain) ?: return HostLoginResult.Cancelled("user-cancelled")
        // 与 UI 主动导入共用同一套白名单 / UA 配对规则。
        return CookieImporter(cookies, { allowedDomains }, nowMs).import(domain, paste)
    }
}
