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

/** Network：`get/post/...` + Cookie 三件套 + 全局 fetch（异步，域名白名单在出口强制）。 */
interface HostNetwork {
    suspend fun get(url: String, headers: Map<String, String>): HostResponse
    suspend fun post(url: String, headers: Map<String, String>, data: String?): HostResponse
    suspend fun getCookies(url: String): List<HostCookie>
    suspend fun setCookies(url: String, list: List<HostCookie>): Boolean
    suspend fun deleteCookies(url: String): Boolean
}

data class HostResponse(val status: Int, val body: String)
data class HostCookie(val name: String, val value: String, val domain: String)

/** Html：受限解析器（须 `dispose()`；复用已 dispose 抛错，见 M1 冻结语义）。 */
interface HostHtml {
    fun parse(html: String): Int // doc id
    fun select(doc: Int, css: String): List<Map<String, String>>
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
