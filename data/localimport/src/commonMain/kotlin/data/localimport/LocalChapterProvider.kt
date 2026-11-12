package data.localimport

import core.model.ChapterPayload
import core.reader.ChapterProvider
import core.reader.ChapterRef
import data.images.FetchedImage
import data.images.ImageFetchException
import data.images.ImageFetcher
import data.images.ImageRequest

/**
 * 本地章节源。对齐 Komikku `ChapterLoader` 四路路由中的本地三路
 * （Directory / Archive / EpubPageLoader），以 [ChapterProvider] 接口形态接入
 * 阅读器状态机；在线 Http 路由仍走源 bridge（`BridgeChapterProvider`）。
 *
 * 章节 id 即扫描 url（`"$manga/$entry"`），页 url 规则：
 * - 目录章节：`"$chapterUrl/$file"`
 * - 归档/EPUB：`"$chapterUrl!/$entryPath"`（`!/` 为归档内分隔）
 * 由 [LocalImageFetcher] 按同一规则反解取字节。
 */
class LocalChapterProvider(
    private val fs: LocalSourceFileSystem,
    private val scanner: LocalSourceScanner = LocalSourceScanner(fs),
    /** workId -> 本地库内漫画目录名（书架/详情落地前由调用方映射）。 */
    private val resolveManga: suspend (workId: String) -> String,
) : ChapterProvider {

    override suspend fun chapters(workId: String): List<ChapterRef> {
        val manga = resolveManga(workId)
        return scanner.scanManga(manga, manga).chapters.mapIndexed { index, c ->
            ChapterRef(id = c.url, title = c.titleOverride ?: c.name, ordinal = index)
        }
    }

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload {
        // 目录章节：含图片即是。
        val dirImages = runCatching {
            fs.listEntries(chapterId)
                .filter { !it.isDirectory && FormatDetector.isImageName(it.name) }
                .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
                .map { "$chapterId/${it.name}" }
        }.getOrNull()
        if (!dirImages.isNullOrEmpty()) return ChapterPayload.Images(dirImages)

        // 归档 / EPUB。
        val reader = try {
            fs.openArchive(chapterId)
        } catch (e: Exception) {
            throw IllegalStateException("无法打开本地章节：$chapterId", e)
        }
        if (chapterId.substringAfterLast('.', "").equals("epub", ignoreCase = true)) {
            val epub = EpubReader(reader)
            val urls = epub.pagePaths().flatMap { page ->
                epub.pageImages(page).map { "$chapterId!/${it.trimStart('/')}" }
            }
            if (urls.isEmpty()) throw IllegalStateException("纯文本 EPUB 随 M4 小说阅读器支持")
            return ChapterPayload.Images(urls)
        }
        val urls = reader.imageEntries().sortedWith(NaturalOrder).map { "$chapterId!/${it.trimStart('/')}" }
        if (urls.isEmpty()) throw IllegalStateException("归档内无图片：$chapterId")
        return ChapterPayload.Images(urls)
    }
}

/**
 * 本地图片抓取（`local://` 隐式 scheme：无 `://` 即视为本地路径）。
 * 归档页按 `!/` 切分（archive 路径 + 条目路径），目录页直接读文件；
 * 非本地 URL 交 [fallback]（在线与本地混合阅读时复用同一出口）。
 */
class LocalImageFetcher(
    private val fs: LocalSourceFileSystem,
    private val fallback: ImageFetcher? = null,
) : ImageFetcher {
    override suspend fun fetch(request: ImageRequest): FetchedImage {
        val url = request.config?.url?.takeIf { it.isNotBlank() } ?: request.url
        val sep = url.indexOf("!/")
        if (sep >= 0) {
            val archivePath = url.substring(0, sep)
            val entry = url.substring(sep + 2)
            return FetchedImage(fs.openArchive(archivePath).readEntry(entry), url)
        }
        if (!url.contains("://")) {
            return FetchedImage(fs.readBytes(url), url)
        }
        val fb = fallback ?: throw ImageFetchException("非本地图片且无网络抓取器：$url")
        return fb.fetch(request)
    }
}
