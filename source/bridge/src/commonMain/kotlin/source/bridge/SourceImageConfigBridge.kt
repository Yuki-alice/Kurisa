package source.bridge

import data.images.ImageLoadingConfig
import data.images.ImageRequest
import source.contract.ImageLoadConfig

/**
 * `comic.onImageLoad` → `:data:images` 抓图管线的接线（docs/plugin-engine.md §7）。
 * 白名单/Cookie/配对 UA/审计仍由 `SourceImageFetcher` 在出口强制；
 * 本层只负责把已 sanitize 的配置翻译成宿主请求。
 */
fun ImageLoadConfig.toImageRequest(originalUrl: String, domains: Set<String>): ImageRequest =
    ImageRequest(
        url = originalUrl,
        domains = domains,
        config = ImageLoadingConfig(
            url = url,
            method = method,
            headers = headers,
            maxRetries = maxRetries,
        ),
    )

/** 源未声明 onImageLoad 时退化为无配置请求（仍走同一白名单出口）。 */
suspend fun SourceRuntime.imageRequest(
    url: String,
    workId: String,
    epId: String,
    domains: Set<String>,
): ImageRequest = (imageLoadConfig(url, workId, epId) ?: ImageLoadConfig())
    .toImageRequest(url, domains)
