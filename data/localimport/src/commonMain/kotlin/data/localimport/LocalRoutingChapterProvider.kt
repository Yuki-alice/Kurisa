package data.localimport

import core.model.ChapterPayload
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReaderPageRequest

/** 本地作品前缀（`ReaderRoute.workId` 侧约定；导航 M5 携带 sourceKey 后可替换为显式路由）。 */
const val LOCAL_WORK_PREFIX = "local:"

/**
 * 本地/在线二路章节源（P2 本地闭环的阅读侧）。
 *
 * 路由规则：
 * - `workId` 以 [LOCAL_WORK_PREFIX] 开头 → 本地源（`resolveManga` 由调用方剥前缀）；
 * - 其余 → 在线源（bridge）。
 * - `resolveNovelImage(url)` 无章节上下文，按 URL 形状路由：仅 `https` 走在线源重写，
 *   本地虚拟路径（`!/`、相对路径）与明文 `http`（渲染层本来就拦截）直接透传，不进 JS。
 *
 * 安全：本地路径永不进 JS 引擎；在线图片仍走 `SourceImageFetcher` 白名单逐跳复核。
 */
class LocalRoutingChapterProvider(
    private val local: ChapterProvider,
    private val online: ChapterProvider,
) : ChapterProvider {

    private fun isLocalWork(workId: String): Boolean = workId.startsWith(LOCAL_WORK_PREFIX)

    private fun target(workId: String): ChapterProvider =
        if (isLocalWork(workId)) local else online

    override suspend fun chapters(workId: String): List<ChapterRef> =
        target(workId).chapters(workId)

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
        target(workId).payload(workId, chapterId)

    override suspend fun resolvePage(workId: String, chapterId: String, url: String): ReaderPageRequest =
        target(workId).resolvePage(workId, chapterId, url)

    override suspend fun rawBytes(workId: String, chapterId: String): ByteArray =
        target(workId).rawBytes(workId, chapterId)

    override suspend fun resolveNovelImage(url: String): ReaderPageRequest =
        if (url.lowercase().startsWith("https://")) online.resolveNovelImage(url)
        else ReaderPageRequest(url)
}
