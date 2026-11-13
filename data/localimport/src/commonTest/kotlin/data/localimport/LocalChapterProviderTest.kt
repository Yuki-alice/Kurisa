package data.localimport

import core.model.ChapterPayload
import data.images.ImageRequest
import korlibs.io.file.std.MemoryVfsMix
import korlibs.io.file.std.asMemoryVfsFile
import korlibs.io.file.std.createZipFromTree
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalChapterProviderTest {

    private val jpg1 = RealImageFixtures.mangaPage(64, 64, 200, 40, 40)
    private val jpg2 = RealImageFixtures.mangaPage(96, 64, 40, 120, 200)

    private suspend fun seed(): LocalSourceFileSystem {
        val cbzSrc = MemoryVfsMix(
            "p001.jpg" to jpg1,
            "p002.jpg" to jpg2,
        )
        val epubSrc = MemoryVfsMix(
            "META-INF/container.xml" to
                """<container><rootfiles><rootfile full-path="OEBPS/c.opf"/></rootfiles></container>""",
            "OEBPS/c.opf" to
                """<package><metadata><dc:title xmlns:dc="urn:x">B</dc:title></metadata>
                  <manifest><item id="p" href="p.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="p"/></spine></package>""",
            "OEBPS/p.xhtml" to """<html><body><img src="i/a.jpg"/><img src="i/b.jpg"/></body></html>""",
        )
        return LocalSourceFileSystem(
            MemoryVfsMix(
                "M/ch02/p1.jpg" to jpg1,
                "M/ch02/p2.jpg" to jpg2,
                "M/ch01/p1.jpg" to jpg1,
                "M/vol.cbz" to cbzSrc.createZipFromTree(),
                "M/book.epub" to epubSrc.createZipFromTree(),
                "M/notes.txt" to "第一章 开端\n正文第一段。\n第二章 继续\n正文第二段。\n".encodeToByteArray(),
            ),
        )
    }

    private fun provider(fs: LocalSourceFileSystem) =
        LocalChapterProvider(fs) { workId -> workId }

    @Test
    fun chaptersDescSkipsNonChapters() = runTest {
        val chapters = provider(seed()).chapters("M")
        // 漫画/归档章节保持原顺序；文本书以 `<path>#<index>` 追加，不影响漫画章节。
        // 展示名去归档扩展名（.cbz/.epub），id 仍是原路径。
        val comicTitles = chapters.filterNot { it.id.contains('#') }.map { it.title }
        assertEquals(
            listOf("vol", "ch02", "ch01", "book").sortedWith(NaturalOrder).reversed(),
            comicTitles,
        )
        assertTrue(chapters.all { it.id.startsWith("M/") })
        assertTrue(chapters.any { it.id.startsWith("M/notes.txt#") })
    }

    @Test
    fun directoryPayloadListsImages() = runTest {
        val payload = provider(seed()).payload("M", "M/ch02")
        require(payload is ChapterPayload.Images)
        assertEquals(listOf("M/ch02/p1.jpg", "M/ch02/p2.jpg"), payload.urls)
    }

    @Test
    fun archivePayloadListsEntries() = runTest {
        val payload = provider(seed()).payload("M", "M/vol.cbz")
        require(payload is ChapterPayload.Images)
        assertEquals(listOf("M/vol.cbz!/p001.jpg", "M/vol.cbz!/p002.jpg"), payload.urls)
    }

    @Test
    fun epubPayloadFlattensSpineImages() = runTest {
        val payload = provider(seed()).payload("M", "M/book.epub")
        require(payload is ChapterPayload.Images)
        assertEquals(listOf("M/book.epub!/OEBPS/i/a.jpg", "M/book.epub!/OEBPS/i/b.jpg"), payload.urls)
    }

    @Test
    fun fetcherReadsDirectoryAndArchive() = runTest {
        val fs = seed()
        val fetcher = LocalImageFetcher(fs)
        assertEquals(jpg1.toList(), fetcher.fetch(ImageRequest("M/ch02/p1.jpg", emptySet())).bytes.toList())
        assertEquals(jpg2.toList(), fetcher.fetch(ImageRequest("M/vol.cbz!/p002.jpg", emptySet())).bytes.toList())
    }

    @Test
    fun pdfChapterListedWithRawBytes() = runTest {
        val fs = LocalSourceFileSystem(
            MemoryVfsMix(
                "Doc/book.pdf" to MinimalPdf.onePage(),
                "Doc/ch01/p1.jpg" to byteArrayOf(1),
            ),
        )
        val provider = LocalChapterProvider(fs) { "Doc" }
        val chapters = provider.chapters("Doc")
        assertTrue(chapters.any { it.id == "Doc/book.pdf" })
        // PDF 不走页管线：payload 明确指引专用阅读器。
        val err = runCatching { provider.payload("Doc", "Doc/book.pdf") }.exceptionOrNull()
        assertTrue(err is IllegalStateException && err.message!!.contains("专用阅读器"))
        // 直读字节完整。
        assertTrue(provider.rawBytes("Doc", "Doc/book.pdf").isNotEmpty())
    }

    @Test
    fun fetcherRejectsRemoteWithoutFallback() = runTest {
        val fetcher = LocalImageFetcher(seed())
        assertFailsWith<Exception> {
            fetcher.fetch(ImageRequest("https://example.com/a.jpg", emptySet()))
        }
    }
}
