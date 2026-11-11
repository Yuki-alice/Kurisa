package data.localimport

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatDetectorTest {

    @Test
    fun extensionFirst() {
        assertEquals(LocalFormat.EPUB, FormatDetector.byExtension("a.EPUB"))
        assertEquals(LocalFormat.TXT, FormatDetector.byExtension("note.txt"))
        assertEquals(LocalFormat.CBZ, FormatDetector.byExtension("v1.cbz"))
        assertEquals(LocalFormat.PDF, FormatDetector.byExtension("doc.pdf"))
        assertEquals(LocalFormat.UNKNOWN, FormatDetector.byExtension("noext"))
    }

    @Test
    fun magicFallback() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00)
        assertEquals(LocalFormat.ZIP, FormatDetector.byMagic(zip))
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)
        assertEquals(LocalFormat.PDF, FormatDetector.byMagic(pdf))
        assertEquals(LocalFormat.UNKNOWN, FormatDetector.byMagic(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun zipContainerRefinedByExtension() {
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertEquals(LocalFormat.EPUB, FormatDetector.detect("b.epub", zip))
        assertEquals(LocalFormat.CBZ, FormatDetector.detect("b.cbz", zip))
        assertEquals(LocalFormat.ZIP, FormatDetector.detect("b.zip", zip))
    }
}
