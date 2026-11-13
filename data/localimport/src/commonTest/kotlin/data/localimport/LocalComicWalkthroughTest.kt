package data.localimport

import core.model.ChapterPayload
import core.reader.ChapterProvider
import core.reader.ReaderPageRequest
import data.images.ImageRequest
import korlibs.io.file.std.MemoryVfsMix
import korlibs.io.file.std.createZipFromTree
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 本地漫画端到端 walkthrough（`docs/comic-e2e-plan.md` §3.1 的自动回归）。
 *
 * 内存 Vfs 生成 CBZ fixture（不落仓、不含站点内容，C1），覆盖：
 * 扫描 → 章节列表 → 目录/在线组合器按 `local:` 前缀分流 → 归档内图片 URL → 取字节。
 */
class LocalComicWalkthroughTest {

    private class ThrowingOnline : ChapterProvider {
        override suspend fun chapters(workId: String) = error("本地作品不应走在线源")
        override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
            error("本地作品不应走在线源")
        override suspend fun rawBytes(workId: String, chapterId: String): ByteArray =
            error("本地作品不应走在线源")
        override suspend fun resolvePage(workId: String, chapterId: String, url: String): ReaderPageRequest =
            error("本地作品不应走在线源")
        override suspend fun resolveNovelImage(url: String): ReaderPageRequest =
            error("本地作品不应走在线源")
    }

    @Test
    fun localCbzWalkthroughReachesPageBytes() = runTest {
        val jpg = RealImageFixtures.mangaPage(64, 64, 200, 40, 40)
        val cbz = MemoryVfsMix(
            "p001.jpg" to jpg,
            "p002.jpg" to jpg,
        ).createZipFromTree()
        val fs = LocalSourceFileSystem(MemoryVfsMix("M/vol.cbz" to cbz))
        val routing = LocalRoutingChapterProvider(
            local = LocalChapterProvider(fs) { it.removePrefix(LOCAL_WORK_PREFIX) },
            online = ThrowingOnline(),
        )

        // 1) 组合器按 local: 前缀分流，扫描出 CBZ 章节。
        val chapters = routing.chapters("local:M")
        val chapterId = chapters.single { it.id.endsWith("vol.cbz") }.id
        assertEquals("M/vol.cbz", chapterId)

        // 2) 章节正文解析为归档内图片 URL。
        val payload = routing.payload("local:M", chapterId)
        require(payload is ChapterPayload.Images) { "CBZ 应产图片页：${payload::class.simpleName}" }
        assertEquals(listOf("M/vol.cbz!/p001.jpg", "M/vol.cbz!/p002.jpg"), payload.urls)

        // 3) 图片出口按 `!/` 反解归档取字节，与 fixture 一致（断网可用）。
        val fetched = LocalImageFetcher(fs).fetch(ImageRequest(payload.urls.first(), emptySet()))
        assertEquals(jpg.toList(), fetched.bytes.toList())
        assertTrue(fetched.bytes.isNotEmpty())
    }
}
