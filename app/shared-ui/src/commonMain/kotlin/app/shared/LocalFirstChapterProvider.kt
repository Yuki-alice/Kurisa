package app.shared

import core.model.ChapterPayload
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReaderPageRequest
import data.localimport.localMangaName

/**
 * 本地优先的章节提供方（B1/B2 的组合器）。
 *
 * 分派依据是 workId 前缀（`local:`，见 [data.localimport.LOCAL_WORK_PREFIX]）：
 * - 本地作品走 [LocalChapterProvider]（目录 / 归档 / EPUB / PDF 直读）；
 * - 其它 workId 原样交给在线源（registry provider，按 sourceKey 绑定）。
 *
 * 为什么不用一个 Provider 槽位装两边：阅读器只认识 [ChapterProvider]，
 * 而 `chapters()` 的 workId 已是 sha256 稳定 id，无法反推来源；前缀是唯一
 * 无需改接口、且对历史 bookshelf 数据零迁移代价的分派方式。
 */
class LocalFirstChapterProvider(
    private val local: ChapterProvider,
    private val remote: ChapterProvider,
) : ChapterProvider {

    private fun delegate(workId: String): ChapterProvider =
        if (localMangaName(workId) != null) local else remote

    override suspend fun chapters(workId: String): List<ChapterRef> =
        delegate(workId).chapters(workId)

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
        delegate(workId).payload(workId, chapterId)

    override suspend fun resolvePage(
        workId: String,
        chapterId: String,
        url: String,
    ): ReaderPageRequest = delegate(workId).resolvePage(workId, chapterId, url)

    override suspend fun rawBytes(workId: String, chapterId: String): ByteArray =
        delegate(workId).rawBytes(workId, chapterId)
}
