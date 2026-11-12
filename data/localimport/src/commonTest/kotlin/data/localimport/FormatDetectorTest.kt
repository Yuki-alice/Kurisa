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

    @Test
    fun directoryShortCircuits() {
        assertEquals(
            LocalFormat.DIRECTORY,
            FormatDetector.detect("anything", byteArrayOf(), isDirectory = true),
        )
    }

    @Test
    fun archiveFamilies() {
        assertEquals(LocalFormat.RAR, FormatDetector.byExtension("v1.cbr"))
        assertEquals(LocalFormat.SEVEN_ZIP, FormatDetector.byExtension("v1.cb7"))
        assertEquals(LocalFormat.TAR, FormatDetector.byExtension("v1.cbt"))
        val rar = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)
        assertEquals(LocalFormat.RAR, FormatDetector.byMagic(rar))
        val sevenZ = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        assertEquals(LocalFormat.SEVEN_ZIP, FormatDetector.byMagic(sevenZ))
    }

    @Test
    fun supportedArchivesAreZipFamily() {
        assertEquals(true, FormatDetector.isSupportedArchive(LocalFormat.ZIP))
        assertEquals(true, FormatDetector.isSupportedArchive(LocalFormat.CBZ))
        assertEquals(true, FormatDetector.isSupportedArchive(LocalFormat.EPUB))
        assertEquals(false, FormatDetector.isSupportedArchive(LocalFormat.RAR))
        assertEquals(false, FormatDetector.isSupportedArchive(LocalFormat.SEVEN_ZIP))
        assertEquals(false, FormatDetector.isSupportedArchive(LocalFormat.TAR))
        assertEquals(false, FormatDetector.isSupportedArchive(LocalFormat.PDF))
    }

    @Test
    fun requireKnownThrowsOnUnknown() {
        try {
            FormatDetector.requireKnown("x.xyz", LocalFormat.UNKNOWN)
            error("expected UnknownFormatException")
        } catch (e: UnknownFormatException) {
            assertEquals(true, e.message!!.contains("x.xyz"))
        }
        assertEquals(
            LocalFormat.CBZ,
            FormatDetector.requireKnown("v.cbz", LocalFormat.CBZ),
        )
    }
}
