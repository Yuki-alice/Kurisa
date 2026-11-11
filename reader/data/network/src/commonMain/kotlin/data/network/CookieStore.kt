package data.network

import core.model.CookieSource
import core.model.StoredCookie

/**
 * Cookie 仓库（M0 CookieStore 的产品化，语义冻结）。
 *
 * 匹配规则（RFC 6265 简化）：域后缀匹配 + 路径前缀匹配 + 过期过滤。
 * 真实落盘为 `cookie` 表（见 :data:db）；此处为内存语义，三端一致可单测。
 *
 * 架构红线：`cf_clearance` 与 UA 绑定存储（pairedUserAgent），不得改为裸 Cookie 复用。
 * 失效策略：Cloudflare 重新挑战只作废 clearance 类，不动登录 session。
 */
class CookieStore {
    private val items = mutableListOf<StoredCookie>()

    fun put(cookie: StoredCookie) {
        items.removeAll {
            it.domain.equals(cookie.domain, true) && it.name == cookie.name && it.path == cookie.path
        }
        items += cookie
    }

    fun putAll(list: List<StoredCookie>) = list.forEach(::put)

    fun all(): List<StoredCookie> = items.toList()

    fun matching(url: String, nowMs: Long): List<StoredCookie> {
        val host = hostOf(url)
        val path = pathOf(url)
        return items.filter { c ->
            val exp = c.expiresAtEpochMs
            c.invalidReason == null &&
                (exp == null || exp > nowMs) &&
                hostMatches(host, c.domain) &&
                pathMatches(path, c.path)
        }
    }

    fun headerFor(url: String, nowMs: Long): String? {
        val list = matching(url, nowMs)
        if (list.isEmpty()) return null
        return list.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun pairedUserAgentFor(url: String, nowMs: Long): String? =
        matching(url, nowMs).firstOrNull {
            (it.name == "cf_clearance" || it.name == "__cf_bm") && !it.pairedUserAgent.isNullOrBlank()
        }?.pairedUserAgent

    fun markInvalid(domain: String, reason: String, only: Set<String>? = null): Int {
        var n = 0
        // 不用 MutableList.replaceAll：Kotlin/Native 实验 API，iOS 编译失败
        for (i in items.indices) {
            val c = items[i]
            val sameSite = c.domain.removePrefix(".").equals(domain.removePrefix("."), true)
            val inScope = only == null || c.name in only
            if (sameSite && inScope && c.invalidReason == null) {
                items[i] = c.copy(invalidReason = reason)
                n++
            }
        }
        return n
    }

    fun remove(host: String): Int {
        val before = items.size
        items.removeAll { it.domain.removePrefix(".").equals(host.removePrefix("."), true) }
        return before - items.size
    }

    fun clear() = items.clear()

    companion object {
        val CF_COOKIES = setOf("cf_clearance", "__cf_bm")

        fun hostOf(url: String): String =
            url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()

        fun pathOf(url: String): String {
            val afterHost = url.substringAfter("://", "").substringAfter('/', "")
            return if (afterHost.isEmpty()) "/" else "/" + afterHost.substringBefore('?').substringBefore('#')
        }

        fun hostMatches(host: String, cookieDomain: String): Boolean {
            val d = cookieDomain.removePrefix(".").lowercase()
            return if (cookieDomain.startsWith(".")) {
                host == d || host.endsWith(".$d")
            } else {
                host == d
            }
        }

        fun pathMatches(requestPath: String, cookiePath: String): Boolean =
            requestPath == cookiePath ||
                requestPath.startsWith(cookiePath.trimEnd('/') + "/") ||
                cookiePath == "/"
    }
}

/** 供测试与 SEED 场景的便捷构造。 */
fun seedCookie(
    domain: String,
    name: String,
    value: String,
    nowMs: Long = 0L,
    pairedUserAgent: String? = null,
) = StoredCookie(
    domain = domain,
    name = name,
    value = value,
    obtainedAtEpochMs = nowMs,
    source = CookieSource.SEED,
    pairedUserAgent = pairedUserAgent,
)
