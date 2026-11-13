package data.localimport

import core.model.ChapterPayload
import core.model.TextFormat
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
 * M4 Phase 2：新增文本路线——`.txt` 按章节标题切分，纯文本 `.epub` 按 spine 切分，
 * 统一产出 [ChapterPayload.Text]；章节 id 用 `<文件路径>#<序号>` 标记子章节。
 * 含图片的 EPUB / 归档 / 目录仍产 [ChapterPayload.Images]，漫画路径不变。
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
        val chapters = scanner.scanManga(manga, manga).chapters.mapIndexed { index, c ->
            ChapterRef(id = c.url, title = c.titleOverride ?: c.name, ordinal = index)
        }.toMutableList()
        // 文本书章节：扫描器按漫画目录语义过滤了 TXT，这里在同构章节列表后追加。
        for (entry in fs.mangaEntries(manga)) {
            if (entry.isDirectory) continue
            val path = "$manga/${entry.name}"
            when (FormatDetector.byExtension(entry.name)) {
                LocalFormat.TXT -> {
                    val book = runCatching {
                        TxtReader.parse(fs.readBytes(path), entry.name.substringBeforeLast('.'))
                    }.getOrDefault(emptyList())
                    book.forEachIndexed { index, chapter ->
                        chapters += ChapterRef("$path#$index", chapter.title, chapters.size)
                    }
                }
                LocalFormat.EPUB -> {
                    val reader = runCatching { EpubTextReader(fs.openArchive(path), path) }.getOrNull()
                        ?: continue
                    if (!reader.hasImages()) {
                        // 纯文本 EPUB 不再是图片章节：移除扫描器按漫画语义生成的同路径条目。
                        chapters.removeAll { it.id == path }
                        repeat(reader.pageCount()) { index ->
                            chapters += ChapterRef("$path#$index", "第 ${index + 1} 节", chapters.size)
                        }
                    }
                }
                else -> Unit
            }
        }
        return chapters
    }

    override suspend fun rawBytes(workId: String, chapterId: String): ByteArray =
        fs.readBytes(splitChapterId(chapterId).first)

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload {
        val (path, textIndex) = splitChapterId(chapterId)
        val format = FormatDetector.byExtension(path)
        if (textIndex != null) {
            when (format) {
                LocalFormat.TXT -> {
                    val book = TxtReader.parse(fs.readBytes(path), path.substringBeforeLast('.'))
                    val chapter = book.getOrNull(textIndex) ?: book.lastOrNull() ?: TxtChapter("正文", "")
                    return ChapterPayload.Text(chapter.body, TextFormat.PLAIN)
                }
                LocalFormat.EPUB -> {
                    val reader = EpubTextReader(fs.openArchive(path), path)
                    return ChapterPayload.Text(reader.chapterHtml(textIndex), TextFormat.HTML)
                }
                else -> Unit
            }
        }

        // 目录章节：含图片即是。
        val dirImages = runCatching {
            fs.listEntries(path)
                .filter { !it.isDirectory && FormatDetector.isImageName(it.name) }
                .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
                .map { "$path/${it.name}" }
        }.getOrNull()
        if (!dirImages.isNullOrEmpty()) return ChapterPayload.Images(dirImages)

        // PDF 不走页管线（见 ChapterProvider.rawBytes + 专用 PDF 阅读器）。
        if (format == LocalFormat.PDF) {
            throw IllegalStateException("PDF 章节请用专用阅读器打开：$chapterId")
        }

        // 归档 / EPUB。
        val reader = try {
            fs.openArchive(path)
        } catch (e: Exception) {
            throw IllegalStateException("无法打开本地章节：$chapterId", e)
        }
        if (format == LocalFormat.EPUB) {
            val epub = EpubReader(reader)
            val urls = epub.pagePaths().flatMap { page ->
                epub.pageImages(page).map { "$path!/${it.trimStart('/')}" }
            }
            if (urls.isEmpty()) {
                // 纯文本 EPUB：整本作为首章正文（带 # 的章节按 spine 逐页读取）。
                return ChapterPayload.Text(
                    EpubTextReader(reader, path).chapterHtml(textIndex ?: 0),
                    TextFormat.HTML,
                )
            }
            return ChapterPayload.Images(urls)
        }
        val urls = reader.imageEntries().sortedWith(NaturalOrder).map { "$path!/${it.trimStart('/')}" }
        if (urls.isEmpty()) throw IllegalStateException("归档内无图片：$chapterId")
        return ChapterPayload.Images(urls)
    }

    /** `<文件路径>#<序号>` → 真实路径 + 子章节序号；无后缀则为漫画/旧章节。 */
    private fun splitChapterId(id: String): Pair<String, Int?> {
        val at = id.lastIndexOf('#')
        if (at < 0) return id to null
        val index = id.substring(at + 1).toIntOrNull() ?: return id to null
        return id.substring(0, at) to index
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
