package data.images

import data.network.SourceHttpClient
import io.ktor.http.HttpStatusCode

/**
 * 图片请求（调用方显式给域名白名单；宿主统一注入 UA/Cookie，禁止绕过）。
 */
data class ImageRequest(
    val url: String,
    val domains: Set<String>,
    val headers: Map<String, String> = emptyMap(),
    val config: ImageLoadingConfig? = null,
)

/**
 * `onImageLoad` 兼容配置（Venera `ImageLoadingConfig` 的宿主侧形状）。
 * `modifyImage` 脚本在独立 context + 独立超时执行（架构 6.2），
 * 落到本层时已是纯字节变换函数；失败不影响主阅读器。
 */
data class ImageLoadingConfig(
    /** 重写 URL（为空则用请求 URL）。 */
    val url: String? = null,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    /** `onResponse` 字节变换（如解密图片），null 为直通。 */
    val transform: ((ByteArray) -> ByteArray)? = null,
    /** `onLoadFailed` 重试次数（不含首次，总尝试 ≤4）。 */
    val maxRetries: Int = 1,
)

/** 图片抓取结果（字节 + 落点 URL；缓存键由调用方按最终 URL 计算）。 */
data class FetchedImage(
    val bytes: ByteArray,
    val finalUrl: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FetchedImage) return false
        return bytes.contentEquals(other.bytes) && finalUrl == other.finalUrl
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + finalUrl.hashCode()
}

/** 抓取失败（Cloudflare 需浏览器验证时 hint 明确告知，不伪装成普通网络错）。 */
class ImageFetchException(message: String) : Exception(message)

/**
 * 源图片抓取（经 [SourceHttpClient]，白名单/Cookie/配对 UA/审计全继承）。
 * 重定向由出口逐跳复核；本层只加“状态码门限 + 变换 + 有界重试”。
 */
interface ImageFetcher {
    suspend fun fetch(request: ImageRequest): FetchedImage
}

class SourceImageFetcher(
    private val http: SourceHttpClient,
) : ImageFetcher {
    override suspend fun fetch(request: ImageRequest): FetchedImage {
        val cfg = request.config
        val target = cfg?.url ?: request.url
        val headers = request.headers + (cfg?.headers.orEmpty())
        val attempts = (cfg?.maxRetries ?: 1).coerceIn(0, 3) + 1
        var lastError: Exception? = null
        repeat(attempts) {
            try {
                val r = http.getBytes(target, request.domains, headers)
                if (r.verdict.needsBrowser) {
                    throw ImageFetchException("需要浏览器验证：${r.verdict.hint}")
                }
                if (r.status != HttpStatusCode.OK.value) {
                    throw ImageFetchException("http:${r.status}")
                }
                val bytes = cfg?.transform?.invoke(r.bytes) ?: r.bytes
                return FetchedImage(bytes, r.finalUrl)
            } catch (e: ImageFetchException) {
                lastError = e
            } catch (e: IllegalArgumentException) {
                // 白名单/重定向拒绝：确定性失败，不重试。
                throw e
            } catch (e: Exception) {
                lastError = ImageFetchException("抓取失败：${e.message}")
            }
        }
        throw lastError ?: ImageFetchException("抓取失败")
    }
}
