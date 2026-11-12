package data.localimport

import korlibs.io.file.std.MemoryVfsMix
import korlibs.io.file.std.asMemoryVfsFile
import korlibs.io.file.std.createZipFromTree
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSourceScannerTest {

    private suspend fun seed(): LocalSourceFileSystem {
        val cbzSrc = MemoryVfsMix(
            "ComicInfo.xml" to "<ComicInfo><Title>Vol One</Title><Number>1</Number></ComicInfo>",
            "p001.jpg" to byteArrayOf(1),
            "p002.jpg" to byteArrayOf(2),
        )
        val cbzBytes = cbzSrc.createZipFromTree()
        val mem = MemoryVfsMix(
            "MangaA/ComicInfo.xml" to
                "<ComicInfo><Series>Manga A</Series><Writer>Author A</Writer>" +
                "<Summary>Desc A</Summary><Genre>Action</Genre></ComicInfo>",
            "MangaA/ch01/p001.jpg" to byteArrayOf(1, 2),
            "MangaA/ch01/p002.jpg" to byteArrayOf(3, 4),
            "MangaA/ch02/ComicInfo.xml" to
                "<ComicInfo><Title>Second</Title><Number>2.5</Number></ComicInfo>",
            "MangaA/ch02/p001.jpg" to byteArrayOf(5),
            "MangaA/cover.jpg" to byteArrayOf(9, 9),
            "MangaB/vol1.cbz" to cbzBytes,
            "notes.txt" to byteArrayOf(0),
        )
        mem[".hiddenManga"].mkdir()
        return LocalSourceFileSystem(mem)
    }

    @Test
    fun scanBaseTitleOrderFiltersHiddenAndFiles() = runTest {
        val fs = seed()
        val scanner = LocalSourceScanner(fs)
        val names = scanner.scanBase(LocalOrderBy.TITLE).map { it.name }
        assertEquals(listOf("MangaA", "MangaB"), names)
    }

    @Test
    fun scanMangaChaptersDescWithComicInfoOverride() = runTest {
        val scanner = LocalSourceScanner(seed())
        val manga = scanner.scanManga("MangaA", "MangaA")
        assertEquals(listOf("ch02", "ch01"), manga.chapters.map { it.name })
        val ch02 = manga.chapters.first { it.name == "ch02" }
        assertEquals(2.5, ch02.number)
        assertEquals("Second", ch02.titleOverride)
        val ch01 = manga.chapters.first { it.name == "ch01" }
        assertEquals(1.0, ch01.number)
        assertNull(ch01.titleOverride)
    }

    @Test
    fun scanMangaTopDetailsAndCover() = runTest {
        val manga = LocalSourceScanner(seed()).scanManga("MangaA", "MangaA")
        assertEquals("Manga A", manga.details?.title)
        assertEquals("Author A", manga.details?.author)
        assertEquals("Desc A", manga.details?.description)
        assertEquals("MangaA/cover.jpg", manga.coverPath)
        assertEquals(false, manga.hasEncryptedComicInfo)
    }

    @Test
    fun scanCbzChapterWithEmbeddedComicInfo() = runTest {
        val manga = LocalSourceScanner(seed()).scanManga("MangaB", "MangaB")
        assertEquals(1, manga.chapters.size)
        val ch = manga.chapters.single()
        assertEquals("Vol One", ch.titleOverride)
        assertEquals(1.0, ch.number)
        assertNotNull(manga.details)
        // 无顶层封面：取归档首图虚拟路径
        assertTrue(manga.coverPath!!.startsWith("MangaB/vol1.cbz!/"))
    }
}

class ComicInfoTest {
    @Test
    fun parsesFields() {
        val info = ComicInfoParser.parse(
            """<ComicInfo xmlns:xsd="https://example.com/xsd" xmlns:xsi="https://example.com/xsi">
              <Title>Ch One</Title><Series>S</Series><Number>12.5</Number>
              <Summary>D</Summary><Writer>W</Writer><Penciller>P</Penciller>
              <Genre>Action</Genre><Publisher>Group</Publisher>
            </ComicInfo>""",
        )
        assertEquals("Ch One", info.title)
        assertEquals("12.5", info.number)
        val chapter = info.toChapterDetails()
        assertEquals(12.5, chapter.number)
        assertEquals("Group", chapter.scanlator)
        val manga = info.toMangaDetails()
        assertEquals("S", manga.title)
        assertEquals("W", manga.author)
    }
}

class LocalCoverManagerTest {
    @Test
    fun findAndUpdateCover() = runTest {
        val mem = MemoryVfsMix(
            "M/p001.jpg" to byteArrayOf(1),
            "M/Cover.PNG" to byteArrayOf(2),
        )
        val fs = LocalSourceFileSystem(mem)
        val covers = LocalCoverManager(fs)
        // 大小写不敏感命中 Cover.PNG
        assertEquals("M/Cover.PNG", covers.findCover("M"))
        // 更新写 cover.jpg + .nomedia
        assertEquals("M/cover.jpg", covers.updateCover("M", byteArrayOf(7, 7, 7)))
        assertEquals(listOf<Byte>(7, 7, 7), fs.readBytes("M/cover.jpg").toList())
        assertEquals(0, fs.readBytes("M/.nomedia").size)
    }
}

class EpubReaderTest {

    private suspend fun sampleEpub(): EpubReader {
        val src = MemoryVfsMix(
            "META-INF/container.xml" to
                """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
            "OEBPS/content.opf" to
                """<package xmlns="https://example.com/opf" xmlns:dc="https://example.com/dc">
                  <metadata><dc:title>Book</dc:title><dc:creator>Auth</dc:creator>
                  <dc:description>D</dc:description><dc:publisher>Group</dc:publisher>
                  <meta property="dcterms:modified">2024-05-06T07:08:09Z</meta></metadata>
                  <manifest>
                  <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                  <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                  <item id="css" href="style.css" media-type="text/css"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>""",
            "OEBPS/ch1.xhtml" to
                """<html><body><img src="images/p1.jpg"/>
                  <svg><image xlink:href="../shared/p0.png"/></svg></body></html>""",
            "OEBPS/text/ch2.xhtml" to "<html><body><img src=\"../images/p2.jpg\"/></body></html>",
        )
        val bytes = src.createZipFromTree()
        return EpubReader(ArchiveReader.open(bytes.asMemoryVfsFile("b.epub")))
    }

    @Test
    fun spineOrderAndImagesResolved() = runTest {
        val epub = sampleEpub()
        assertEquals("OEBPS/content.opf", epub.packagePath())
        assertEquals(listOf("OEBPS/ch1.xhtml", "OEBPS/text/ch2.xhtml"), epub.pagePaths())
        assertEquals(
            listOf("OEBPS/images/p1.jpg", "shared/p0.png"),
            epub.pageImages("OEBPS/ch1.xhtml"),
        )
        assertEquals(listOf("OEBPS/images/p2.jpg"), epub.pageImages("OEBPS/text/ch2.xhtml"))
    }

    @Test
    fun metadataFilled() = runTest {
        val meta = sampleEpub().metadata()
        assertEquals("Book", meta.title)
        assertEquals("Auth", meta.creator)
        assertEquals("Group", meta.publisher)
        assertNotNull(meta.dateMillis)
    }

    @Test
    fun resolveZipPathCases() {
        assertEquals("a/b/c.jpg", EpubReader.resolveZipPath("a/x.html", "b/c.jpg"))
        assertEquals("a/c.jpg", EpubReader.resolveZipPath("a/b/x.html", "../c.jpg"))
        assertEquals("root.jpg", EpubReader.resolveZipPath("a/x.html", "/root.jpg"))
        assertEquals("a/b.jpg", EpubReader.resolveZipPath("a/x.html", "b.jpg#frag"))
    }

    @Test
    fun parseEpubDate() {
        // 2024-05-06T07:08:09Z = 1714979289
        assertEquals(1_714_979_289_000L, EpubReader.parseEpubDate("2024-05-06T07:08:09Z"))
        assertEquals(1_714_979_289_000L, EpubReader.parseEpubDate("2024-05-06T09:08:09+02:00"))
        assertEquals(null, EpubReader.parseEpubDate("not-a-date"))
    }
}
