package source.hostapi

/**
 * Host API 门面（宿主注入 JS 上下文的全局对象集合，架构 4.5）。
 * 执行方式：Convert/Html/Utils/Storage/Novel 同步；Network/UI 异步 Promise。
 * 完整实现随源运行时落地；此处冻结接口形状，bridge 只对接口编程。
 */
interface HostApi {
    val network: HostNetwork
    val html: HostHtml
    val convert: HostConvert
    val ui: HostUi
    val utils: HostUtils
    val storage: HostStorage
    val novel: HostNovel
}

/** Network：全方法 + Cookie 三件套 + 全局 fetch（异步，域名白名单在出口强制）。 */
interface HostNetwork {
    suspend fun get(url: String, headers: Map<String, String>): HostResponse
    suspend fun post(url: String, headers: Map<String, String>, data: String?): HostResponse
    suspend fun put(url: String, headers: Map<String, String>, data: String?): HostResponse =
        sendRequest("PUT", url, headers, data)
    suspend fun patch(url: String, headers: Map<String, String>, data: String?): HostResponse =
        sendRequest("PATCH", url, headers, data)
    suspend fun delete(url: String, headers: Map<String, String>): HostResponse =
        sendRequest("DELETE", url, headers, null)
    suspend fun sendRequest(method: String, url: String, headers: Map<String, String>, data: String?): HostResponse =
        when (method.uppercase()) {
            "POST" -> post(url, headers, data)
            else -> get(url, headers)
        }
    /** 批量请求（P1：宿主 IO 并发 + per-host 限流 + GET 缓存 + in-flight 去重；保序返回）。 */
    suspend fun requestAll(requests: List<HostRequest>): List<HostResponse> =
        requests.map { sendRequest(it.method, it.url, it.headers, it.data) }
    suspend fun getCookies(url: String): List<HostCookie>
    suspend fun setCookies(url: String, list: List<HostCookie>): Boolean
    suspend fun deleteCookies(url: String): Boolean
}

/** 批量请求项（method 仅 GET/POST/PUT/PATCH/DELETE；未知由宿主按 CONTRACT 拒绝）。 */
data class HostRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val data: String? = null,
)

/** 统一响应（P1：回传 headers/finalUrl/verdict/truncated，旧 JS 只读 status/body 仍兼容）。 */
data class HostResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    val finalUrl: String = "",
    val verdict: String? = null,
    val truncated: Boolean = false,
)
data class HostCookie(val name: String, val value: String, val domain: String)

/** Html：受限解析器（须 `dispose()`；复用已 dispose 抛错，见 M1 冻结语义）。 */
interface HostHtml {
    fun parse(html: String): Int // doc id
    fun select(doc: Int, css: String): List<Map<String, String>>
    /** 批量选择（P1：一次跨边界多选择器；默认逐个 select，Ksoup 实现可复用同一 Document）。 */
    fun selectMany(doc: Int, queries: List<String>): List<List<Map<String, String>>> =
        queries.map { select(doc, it) }
    fun dispose(doc: Int)
}

/** Convert：纯 Kotlin 编解码/哈希（md5/sha/base64/AES…），不经网络。 */
interface HostConvert {
    fun md5(text: String): String
    fun sha256(text: String): String
    fun encodeBase64(bytes: ByteArray): String
    fun decodeBase64(text: String): ByteArray
}

/** UI：需 UI 上下文；桌面端降级（ADR-009）。 */
interface HostUi {
    suspend fun showMessage(text: String)
    suspend fun launchUrl(url: String)
}

/** Utils：uuid/随机数/日志（同步）。 */
interface HostUtils {
    fun createUuid(): String
    fun log(text: String)
}

/** Storage：源私有 KV（落 `source_kv` 表，以 source_key 隔离，禁止跨源读取）。 */
interface HostStorage {
    fun get(sourceKey: String, key: String): String?
    fun set(sourceKey: String, key: String, value: String)
    fun delete(sourceKey: String, key: String)
}

/** Novel：编码探测/HTML 清洗/章节切分（同步）。 */
interface HostNovel {
    fun stripHtml(html: String): String
}
