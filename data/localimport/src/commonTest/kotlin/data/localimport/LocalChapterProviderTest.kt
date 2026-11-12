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

    private suspend fun seed(): LocalSourceFileSystem {
        val cbzSrc = MemoryVfsMix(
            "p001.jpg" to byteArrayOf(1),
            "p002.jpg" to byteArrayOf(2),
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
                "M/ch02/p1.jpg" to byteArrayOf(1),
                "M/ch02/p2.jpg" to byteArrayOf(2),
                "M/ch01/p1.jpg" to byteArrayOf(3),
                "M/vol.cbz" to cbzSrc.createZipFromTree(),
                "M/book.epub" to epubSrc.createZipFromTree(),
                "M/notes.txt" to byteArrayOf(0),
            ),
        )
    }

    private fun provider(fs: LocalSourceFileSystem) =
        LocalChapterProvider(fs) { workId -> workId }

    @Test
    fun chaptersDescSkipsNonChapters() = runTest {
        val chapters = provider(seed()).chapters("M")
        assertEquals(listOf("vol.cbz", "ch02", "ch01", "book.epub").sortedWith(NaturalOrder).reversed(), chapters.map { it.title })
        assertTrue(chapters.all { it.id.startsWith("M/") })
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
        assertEquals(listOf<Byte>(1), fetcher.fetch(ImageRequest("M/ch02/p1.jpg", emptySet())).bytes.toList())
        assertEquals(listOf<Byte>(2), fetcher.fetch(ImageRequest("M/vol.cbz!/p002.jpg", emptySet())).bytes.toList())
    }

    @Test
    fun fetcherRejectsRemoteWithoutFallback() = runTest {
        val fetcher = LocalImageFetcher(seed())
        assertFailsWith<Exception> {
            fetcher.fetch(ImageRequest("https://example.com/a.jpg", emptySet()))
        }
    }
}
