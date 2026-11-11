package source.bridge

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import core.model.toUtf8Bytes
import data.network.CookieStore
import data.network.SourceHttpClient
import source.hostapi.HostConvert
import source.hostapi.HostCookie
import source.hostapi.HostHtml
import source.hostapi.HostNetwork
import source.hostapi.HostNovel
import source.hostapi.HostResponse
import source.hostapi.HostStorage
import source.hostapi.HostUi
import source.hostapi.HostUtils
import kotlin.random.Random

/**
 * HostApi 七命名空间实现（M1 harness 产品化；行为与冻结语义一致）。
 *
 * - Network：经 [SourceHttpClient]，白名单/Cookie/配对 UA/审计全继承；
 *   域名在引擎创建时按源版本绑定（不可变，防运行时篡改）。
 * - Html：Ksoup 受限解析；`dispose()` 后复用抛错（M1 冻结语义）。
 * - Convert：korlibs（与 M0 同族库），纯本地。
 * - Storage：调用方注入（生产为 `:data:repository` 的 DbKvStore，测试用内存版）。
 * - UI：默认拒绝并指路（WebView 登录需用户手势+独立上下文，M6 落地）。
 */
class KtorHostNetwork(
    private val http: SourceHttpClient,
    private val cookies: CookieStore,
    private val domains: Set<String>,
    private val nowMs: () -> Long = { 0L },
) : HostNetwork {
    override suspend fun get(url: String, headers: Map<String, String>): HostResponse {
        val r = http.get(url, domains, headers)
        return HostResponse(r.status, r.body)
    }

    override suspend fun post(url: String, headers: Map<String, String>, data: String?): HostResponse {
        val r = http.post(url, domains, headers, data)
        return HostResponse(r.status, r.body)
    }

    override suspend fun getCookies(url: String): List<HostCookie> =
        cookies.matching(url, nowMs()).map { HostCookie(it.name, it.value, it.domain) }

    override suspend fun setCookies(url: String, list: List<HostCookie>): Boolean {
        val host = CookieStore.hostOf(url)
        list.forEach { c ->
            cookies.put(
                core.model.StoredCookie(
                    domain = c.domain.ifEmpty { host },
                    name = c.name,
                    value = c.value,
                    obtainedAtEpochMs = nowMs(),
                    source = core.model.CookieSource.NETWORK,
                ),
            )
        }
        return true
    }

    override suspend fun deleteCookies(url: String): Boolean {
        cookies.remove(CookieStore.hostOf(url))
        return true
    }
}

class KsoupHostHtml : HostHtml {
    private val docs = mutableMapOf<Int, Document>()
    private var nextDoc = 1

    override fun parse(html: String): Int {
        val id = nextDoc++
        docs[id] = Ksoup.parse(html)
        return id
    }

    override fun select(doc: Int, css: String): List<Map<String, String>> {
        val d = docs[doc] ?: throw IllegalStateException("HtmlDocument disposed-or-unknown: $doc")
        return d.select(css).map { el ->
            buildMap {
                put("text", el.text())
                put("html", el.html())
                el.attributes().forEach { a -> put("attr:" + a.key, a.value) }
            }
        }
    }

    override fun dispose(doc: Int) {
        docs.remove(doc)
    }

    fun liveCount(): Int = docs.size
}

class CryptoHostConvert : HostConvert {
    override fun md5(text: String): String = Md5.hex(text.toUtf8Bytes())
    override fun sha256(text: String): String = core.model.sha256Hex(text)
    override fun encodeBase64(bytes: ByteArray): String = Base64Codec.encode(bytes)
    override fun decodeBase64(text: String): ByteArray =
        Base64Codec.decode(text) ?: throw IllegalArgumentException("base64 非法")
}

class NoopHostUi(private val reason: String = "WebView 登录需 M6 平台实现") : HostUi {
    override suspend fun showMessage(text: String) = Unit
    override suspend fun launchUrl(url: String): Unit =
        throw UnsupportedOperationException(reason)
}

class HostUtilsImpl(
    private val logSink: (String) -> Unit = {},
) : HostUtils {
    override fun createUuid(): String {
        // 非安全随机（M7 切 SecureRandom；此处仅满足契约形状，禁止用于密钥）。
        val r = Random.Default
        return buildString {
            repeat(8) { append("0123456789abcdef"[r.nextInt(16)]) }
            append('-')
            repeat(4) { append("0123456789abcdef"[r.nextInt(16)]) }
            append("-4")
            repeat(3) { append("0123456789abcdef"[r.nextInt(16)]) }
            append('-')
            append("89ab"[r.nextInt(4)])
            repeat(3) { append("0123456789abcdef"[r.nextInt(16)]) }
            append('-')
            repeat(12) { append("0123456789abcdef"[r.nextInt(16)]) }
        }
    }

    override fun log(text: String) = logSink(text)
}

class InMemoryHostStorage : HostStorage {
    private val map = mutableMapOf<String, String>()
    override fun get(sourceKey: String, key: String): String? = map["$sourceKey::$key"]
    override fun set(sourceKey: String, key: String, value: String) {
        map["$sourceKey::$key"] = value
    }

    override fun delete(sourceKey: String, key: String) {
        map.remove("$sourceKey::$key")
    }
}

class NovelHost : HostNovel {
    /**
     * 标签剥离（纯文本抽取；排版分页与受限标签白名单由渲染层负责，见 M4）。
     * script/style 内容一并丢弃（防正文污染）。
     */
    override fun stripHtml(html: String): String {
        // ReDoS 门：超长输入先截断（章节 HTML 正常 <1MB；恶意源的 MB 级载荷不值得全文正则）。
        val capped = if (html.length > 2_000_000) html.take(2_000_000) else html
        val noScript = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(capped, " ")
        // 块级标签先换行（段落语义），再清剩余行内标签。
        val blocked = Regex("(?i)</?(p|div|h[1-6]|li|tr|br)[^>]*>").replace(noScript, "\n")
        val noTags = Regex("<[^>]+>").replace(blocked, " ")
        return noTags
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
}
