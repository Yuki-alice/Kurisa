package data.localimport

import core.model.ChapterPayload
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReaderPageRequest
import korlibs.io.file.std.MemoryVfsMix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 本地/在线二路路由单测（commonTest，纯内存 Vfs + fake 在线源）。
 * 锁死：`local:` 前缀走本地，其余走在线；图片重写按 URL 形状分流。
 */
class LocalRoutingChapterProviderTest {

    private fun seed(): LocalSourceFileSystem = LocalSourceFileSystem(
        MemoryVfsMix(
            "M/notes.txt" to "第一章 开端\n正文第一段。\n第二章 继续\n正文第二段。\n".encodeToByteArray(),
        ),
    )

    private class FakeOnline : ChapterProvider {
        override suspend fun chapters(workId: String) = listOf(ChapterRef("e1", "在线话", 1))
        override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
            ChapterPayload.Images(listOf("https://cdn.example.com/x.jpg"))
        override suspend fun resolvePage(workId: String, chapterId: String, url: String) =
            ReaderPageRequest(url, rewriteUrl = "https://cdn.example.com/r.jpg")
        override suspend fun resolveNovelImage(url: String) =
            ReaderPageRequest(url, rewriteUrl = "https://cdn.example.com/n.jpg")
        override suspend fun rawBytes(workId: String, chapterId: String): ByteArray =
            throw UnsupportedOperationException("online")
    }

    private fun routing(fs: LocalSourceFileSystem) = LocalRoutingChapterProvider(
        local = LocalChapterProvider(fs) { it.removePrefix(LOCAL_WORK_PREFIX) },
        online = FakeOnline(),
    )

    @Test
    fun chaptersRouteByPrefix() = runTest {
        val r = routing(seed())
        val local = r.chapters("local:M")
        assertTrue(local.any { it.id.startsWith("M/notes.txt#") })
        assertEquals(listOf("e1"), r.chapters("w").map { it.id })
    }

    @Test
    fun payloadRouteByPrefix() = runTest {
        val r = routing(seed())
        val text = r.payload("local:M", "M/notes.txt#0") as ChapterPayload.Text
        assertTrue(text.body.contains("正文第一段"))
        val images = r.payload("w", "e1") as ChapterPayload.Images
        assertEquals(listOf("https://cdn.example.com/x.jpg"), images.urls)
    }

    @Test
    fun resolvePageAndRawBytesRouteByPrefix() = runTest {
        val r = routing(seed())
        assertEquals("p1", r.resolvePage("local:M", "M/notes.txt#0", "p1").effectiveUrl)
        assertEquals(
            "https://cdn.example.com/r.jpg",
            r.resolvePage("w", "e1", "https://cdn.example.com/x.jpg").effectiveUrl,
        )
        assertTrue(r.rawBytes("local:M", "M/notes.txt#0").isNotEmpty())
        assertFailsWith<UnsupportedOperationException> { r.rawBytes("w", "e1") }
    }

    @Test
    fun novelImageRoutesByUrlShape() = runTest {
        val r = routing(seed())
        // 本地虚拟路径与明文 http 不进 JS，直接透传。
        assertEquals(
            "M/book.epub!/OEBPS/i/a.jpg",
            r.resolveNovelImage("M/book.epub!/OEBPS/i/a.jpg").effectiveUrl,
        )
        assertEquals(
            "http://example.com/a.jpg",
            r.resolveNovelImage("http://example.com/a.jpg").effectiveUrl,
        )
        // 在线 https 走在线源重写。
        assertEquals(
            "https://cdn.example.com/n.jpg",
            r.resolveNovelImage("https://cdn.example.com/o.jpg").effectiveUrl,
        )
    }
}
