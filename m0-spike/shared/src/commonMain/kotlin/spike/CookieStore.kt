package spike

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Cookie 的来源，决定可信度与是否需要配对 UA。 */
enum class CookieSource {
    /** WebView 内登录/过盾后由系统 CookieManager 导出。 */
    WEBVIEW,

    /** 用户从浏览器 DevTools 手工粘贴。 */
    MANUAL_IMPORT,

    /** 服务端 Set-Cookie 回写。 */
    NETWORK,

    /** 测试或宿主预置。 */
    SEED,
}

/**
 * 一条已存储的 Cookie。
 *
 * `pairedUserAgent` 是 Cloudflare 场景的核心：`cf_clearance` 与赚到它的浏览器 UA 绑定，
 * 换一个 UA 发同一份 Cookie 会被判为重放，直接重新挑战。因此宿主必须把
 * 「Cookie + 产生它的 UA」作为一个整体存取，而不是只存 name/value。
 */
data class StoredCookie(
    val domain: String,
    val name: String,
    val value: String,
    val path: String = "/",
    val expiresAtEpochMs: Long? = null,
    val obtainedAtEpochMs: Long = 0L,
    val source: CookieSource = CookieSource.SEED,
    val pairedUserAgent: String? = null,
    /** 非空表示已被判定失效（例如再次收到挑战），不再参与请求注入。 */
    val invalidReason: String? = null,
)

/**
 * Cookie 仓库。
 *
 * 匹配规则对齐 RFC 6265 的简化版：域后缀匹配 + 路径前缀匹配 + 过期过滤。
 * 真实工程里这张表落 SQLDelight（architecture.md 5.2 的 `cookie` 表），
 * 这里用内存实现承载同样的语义，便于 M0 验证。
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

    /** 当前对该 URL 生效的 Cookie（未过期、未失效、域与路径均匹配）。 */
    fun matching(url: String, nowMs: Long): List<StoredCookie> {
        val host = hostOf(url)
        val path = pathOf(url)
        return items.filter { c ->
            c.invalidReason == null &&
                (c.expiresAtEpochMs == null || c.expiresAtEpochMs > nowMs) &&
                hostMatches(host, c.domain) &&
                pathMatches(path, c.path)
        }
    }

    fun headerFor(url: String, nowMs: Long): String? {
        val list = matching(url, nowMs)
        if (list.isEmpty()) return null
        return list.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * 该 URL 上是否有 Cloudflare clearance 类 Cookie，若有则返回它配对的 UA。
     * 宿主据此覆盖本次请求的 User-Agent，否则 Cookie 必然失效。
     */
    fun pairedUserAgentFor(url: String, nowMs: Long): String? =
        matching(url, nowMs).firstOrNull {
            (it.name == "cf_clearance" || it.name == "__cf_bm") && !it.pairedUserAgent.isNullOrBlank()
        }?.pairedUserAgent

    /**
     * 标记失效。
     *
     * `only` 用于限定范围：Cloudflare 重新挑战时只应作废 clearance 类 Cookie，
     * 登录 session 仍然有效——两者混为一谈会让用户每次过盾后都要重新登录。
     */
    fun markInvalid(domain: String, reason: String, only: Set<String>? = null): Int {
        var n = 0
        // 不用 MutableList.replaceAll：它在 Kotlin/Native 上是实验 API，会让 iOS target 编译失败
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
        /** Cloudflare 的 clearance 类 Cookie 名。重新挑战时只作废这些，不动登录 session。 */
        val CF_COOKIES = setOf("cf_clearance", "__cf_bm")

        fun hostOf(url: String): String =
            url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()

        fun pathOf(url: String): String {
            val afterHost = url.substringAfter("://", "").substringAfter('/', "")
            return if (afterHost.isEmpty()) "/" else "/" + afterHost.substringBefore('?').substringBefore('#')
        }

        fun hostMatches(host: String, cookieDomain: String): Boolean {
            val d = cookieDomain.removePrefix(".").lowercase()
            // 带点前缀允许子域；不带点要求精确匹配（对齐 host-only 语义）
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

/** Cloudflare 拦截的类型。三类必须分开处理，处置方式完全不同。 */
enum class ChallengeKind {
    /** 没有拦截。 */
    NONE,

    /** 托管挑战（"Just a moment..." / "Checking your browser"）。真浏览器过一次即可拿到 clearance。 */
    MANAGED_CHALLENGE,

    /** WAF / IP 声誉阻断（"Sorry, you have been blocked"、error 1020/1012/1009）。Cookie 无效。 */
    WAF_BLOCK,

    /** 限流（429）。退避重试，不要重置 Cookie。 */
    RATE_LIMITED,

    /** Cloudflare 侧 5xx。 */
    CF_OUTAGE,
}

data class Verdict(
    val kind: ChallengeKind,
    /** 是否需要「真实浏览器过一次」来获取 clearance。 */
    val needsBrowser: Boolean,
    /** 是否值得原样重试。 */
    val retryable: Boolean,
    /** 给用户看的一句话。 */
    val hint: String,
)

/**
 * Cloudflare 拦截识别。
 *
 * 关键判据是响应头 `cf-mitigated: challenge`（实测可靠），
 * body 文本仅作兜底——因为不同站点的挑战页模板差异很大。
 *
 * 实测样本见 `src/desktopMain/resources/cf-fixtures/`：
 * - `challenge-managed.html`：403 + `cf-mitigated: challenge` + "Just a moment"
 * - `block-waf.html`：403 + "Sorry, you have been blocked"（数据中心 IP 被 WAF 拒绝）
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

data class ParseResult(
    val cookies: List<StoredCookie>,
    /** 识别出的输入格式，用于给用户回显「我按什么理解了你粘贴的内容」。 */
    val format: String,
    val warnings: List<String>,
)

/**
 * 用户粘贴内容的解析器。
 *
 * 用户从不同地方复制，格式五花八门；这里统一收口，避免每种格式写一套 UI：
 * 1. 请求头形式：`a=1; b=2`
 * 2. DevTools Application → Cookies 表格复制：`name\tvalue\t...`（制表符分隔，多行）
 * 3. 每行一个 `name=value`
 * 4. JSON 数组（浏览器扩展导出）：`[{"name":..,"value":..}]`
 */
object CookieImportParser {

    fun parse(raw: String, domain: String, nowMs: Long, pairedUserAgent: String?): ParseResult {
        val text = raw.trim().removeSurrounding("\"\"\"")
        val warnings = mutableListOf<String>()
        if (text.isEmpty()) return ParseResult(emptyList(), "empty", listOf("粘贴内容为空"))

        val pairs: List<Pair<String, String>> = when {
            text.startsWith("[") || text.startsWith("{") -> {
                val fromJson = parseJson(text, warnings)
                return ParseResult(
                    fromJson.map { (k, v) -> mk(domain, k, v, nowMs, pairedUserAgent) },
                    "json",
                    if (fromJson.isEmpty()) warnings + "JSON 中未找到 name/value 字段" else warnings,
                )
            }

            text.contains('\t') -> {
                // DevTools 表格：第一列 name，第二列 value，其余是 domain/path/expires 等
                text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                    val cols = line.split('\t')
                    if (cols.size < 2) null else cols[0].trim() to cols[1].trim()
                }.also { warnings += "按 DevTools 表格（制表符分隔）解析" }
            }

            text.lines().count { it.contains('=') && it.isNotBlank() } > 1 -> {
                text.lines().filter { it.isNotBlank() && it.contains('=') }.map {
                    it.substringBefore('=').trim() to it.substringAfter('=').trim()
                }.also { warnings += "按多行 name=value 解析" }
            }

            else -> {
                // 单行 Cookie 头
                text.split(';').mapNotNull { part ->
                    val p = part.trim()
                    if (!p.contains('=')) null else p.substringBefore('=').trim() to p.substringAfter('=').trim()
                }
            }
        }

        val cleaned = pairs
            .filter { it.first.isNotEmpty() && it.first.matches(Regex("""[A-Za-z0-9_\-.#$%]+""")) }
            .distinctBy { it.first }

        if (cleaned.isEmpty()) warnings += "未解析出任何合法 Cookie 名"

        val format = when {
            text.contains('\t') -> "tab-separated"
            text.lines().count { it.contains('=') } > 1 -> "multi-line"
            text.contains(';') -> "cookie-header"
            else -> "single-pair"
        }

        return ParseResult(cleaned.map { (k, v) -> mk(domain, k, v, nowMs, pairedUserAgent) }, format, warnings)
    }

    private fun mk(domain: String, name: String, value: String, nowMs: Long, ua: String?) = StoredCookie(
        domain = domain,
        name = name,
        value = value,
        obtainedAtEpochMs = nowMs,
        source = CookieSource.MANUAL_IMPORT,
        pairedUserAgent = ua,
    )

    private fun parseJson(text: String, warnings: MutableList<String>): List<Pair<String, String>> {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            ?: run { warnings += "JSON 解析失败"; return emptyList() }
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["cookies"] as? JsonArray) ?: JsonArray(listOf(element))
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val n = o["name"]?.jsonPrimitive?.contentOrNull
            val v = o["value"]?.jsonPrimitive?.contentOrNull
            if (n.isNullOrBlank() || v == null) null else n to v
        }
    }
}
