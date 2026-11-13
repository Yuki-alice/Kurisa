package data.localimport

import core.model.ChapterPayload
import core.model.TextFormat
import korlibs.io.file.std.MemoryVfsMix
import korlibs.io.file.std.createZipFromTree
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TXT / 文本 EPUB 解析与本地章节源接线（commonTest）。 */
class TextImportTest {

    @Test
    fun txtDecodesUtf8AndSplitsChapterTitles() {
        val text = "第一章 开端\n正文第一段。\n\n第二章 继续\n正文第二段。\n"
        val chapters = TxtReader.parse(text.encodeToByteArray(), "book")
        assertEquals(2, chapters.size)
        assertEquals("第一章 开端", chapters[0].title)
        assertTrue(chapters[0].body.contains("正文第一段"))
        assertEquals("第二章 继续", chapters[1].title)
        assertTrue(chapters[1].body.contains("正文第二段"))
    }

    @Test
    fun txtFallsBackToGbkWhenUtf8IsInvalid() {
        val gbk = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        assertEquals("中文", TxtReader.decode(gbk))
    }

    @Test
    fun txtWithoutTitlesIsSingleChapter() {
        val chapters = TxtReader.parse("只有一段正文\n第二段文字".encodeToByteArray(), "book")
        assertEquals(1, chapters.size)
        assertEquals("book", chapters[0].title)
        assertTrue(chapters[0].body.contains("第二段文字"))
    }

    @Test
    fun txtStripsUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "正文".encodeToByteArray()
        assertEquals("正文", TxtReader.decode(bytes))
    }

    private suspend fun textEpub(): LocalSourceFileSystem {
        val src = MemoryVfsMix(
            "META-INF/container.xml" to
                """<container><rootfiles><rootfile full-path="OEBPS/c.opf"/></rootfiles></container>""",
            "OEBPS/c.opf" to
                """<package><manifest>
                     <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                     <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                   </manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OEBPS/a.xhtml" to """<html><body><h1>第一章</h1><p>正文第一段</p><script>evil()</script></body></html>""",
            "OEBPS/b.xhtml" to """<html><body><p>正文第二段</p></body></html>""",
        )
        return LocalSourceFileSystem(MemoryVfsMix("M/book.epub" to src.createZipFromTree()))
    }

    @Test
    fun textEpubProducesOneChapterPerSpineItem() = runTest {
        val fs = textEpub()
        val provider = LocalChapterProvider(fs) { "M" }
        val chapters = provider.chapters("M")
        assertEquals(listOf("M/book.epub#0", "M/book.epub#1"), chapters.map { it.id })
        val first = provider.payload("M", "M/book.epub#0")
        val second = provider.payload("M", "M/book.epub#1")
        require(first is ChapterPayload.Text)
        require(second is ChapterPayload.Text)
        assertEquals(TextFormat.HTML, first.format)
        assertTrue(first.body.contains("正文第一段"))
        assertTrue(second.body.contains("正文第二段"))
        // 无 # 后缀的旧章节 id 仍可读首章（向后兼容）。
        require(provider.payload("M", "M/book.epub") is ChapterPayload.Text)
    }

    @Test
    fun epubImageSrcIsRewrittenToArchiveVirtualPath() = runTest {
        val src = MemoryVfsMix(
            "META-INF/container.xml" to
                """<container><rootfiles><rootfile full-path="OEBPS/c.opf"/></rootfiles></container>""",
            "OEBPS/c.opf" to
                """<package><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
                   <spine><itemref idref="a"/></spine></package>""",
            "OEBPS/a.xhtml" to """<html><body><p>A</p><img src="i/a.jpg"/></body></html>""",
        )
        val fs = LocalSourceFileSystem(MemoryVfsMix("M/img.epub" to src.createZipFromTree()))
        val reader = EpubTextReader(fs.openArchive("M/img.epub"), "M/img.epub")
        assertTrue(reader.hasImages())
        assertTrue(reader.chapterHtml(0).contains("M/img.epub!/OEBPS/i/a.jpg"))
    }

    @Test
    fun txtChapterIsExposedByProvider() = runTest {
        val text = "第一章 开端\n正文第一段。\n第二章 继续\n正文第二段。\n"
        val fs = LocalSourceFileSystem(MemoryVfsMix("M/novel.txt" to text.encodeToByteArray()))
        val provider = LocalChapterProvider(fs) { "M" }
        val chapters = provider.chapters("M")
        assertEquals(listOf("M/novel.txt#0", "M/novel.txt#1"), chapters.map { it.id })
        val payload = provider.payload("M", "M/novel.txt#1")
        require(payload is ChapterPayload.Text)
        assertEquals(TextFormat.PLAIN, payload.format)
        assertTrue(payload.body.contains("正文第二段"))
    }
}
