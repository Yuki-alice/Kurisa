package data.localimport

import com.fleeksoft.ksoup.Ksoup

/**
 * 文本 EPUB 解析（纯文本/无图片 EPUB 走小说管线；图片 EPUB 仍走漫画图片页）。
 *
 * 复用 [EpubReader] 的 container → OPF manifest/spine 顺序；每个 spine item 视为一章。
 * 输出受限 HTML 字符串交给 `NovelContentParser` 做白名单解析；`script/style` 等
 * 危险内容由该解析层丢弃，不在此执行任何脚本。
 *
 * 页内图片 src 重写为 `chapterId!/<zip 内路径>` 虚拟路径，与 [LocalImageFetcher]
 * 的归档读取约定一致；是否允许加载仍由阅读器侧网络出口的域名白名单决定。
 */
class EpubTextReader(
    private val archive: ArchiveReader,
    private val chapterId: String,
) {
    private val epub = EpubReader(archive)

    /** spine 页面数 = 文本章节数。 */
    suspend fun pageCount(): Int = epub.pagePaths().size

    /** EPUB 内是否含图片（含图片则继续走漫画图片页，避免拆成文本）。 */
    suspend fun hasImages(): Boolean = epub.pagePaths().any { epub.pageImages(it).isNotEmpty() }

    /** 单章受限 HTML；图片路径按 zip 内相对路径归一。 */
    suspend fun chapterHtml(index: Int): String {
        val path = epub.pagePaths().getOrNull(index) ?: return ""
        val doc = Ksoup.parse(archive.readEntry(path).decodeToString())
        doc.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.isNotBlank()) {
                val entry = EpubReader.resolveZipPath(path, src)
                img.attr("src", "$chapterId!/${entry.trimStart('/')}")
            }
        }
        doc.select("image").forEach { image ->
            val href = image.attr("xlink:href").ifBlank { image.attr("href") }
            if (href.isNotBlank()) {
                val entry = EpubReader.resolveZipPath(path, href)
                image.attr("xlink:href", "$chapterId!/${entry.trimStart('/')}")
            }
        }
        return doc.body().html()
    }
}
