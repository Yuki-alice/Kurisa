package data.localimport

import korlibs.io.file.std.MemoryVfsMix
import korlibs.io.file.std.asMemoryVfsFile
import korlibs.io.file.std.createZipFromTree
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ArchiveReaderTest {

    private val jpg1 = RealImageFixtures.mangaPage(64, 64, 200, 40, 40)
    private val jpg2 = RealImageFixtures.mangaPage(64, 96, 40, 120, 200)

    private suspend fun sampleZip(): ArchiveReader {
        val src = MemoryVfsMix(
            "p002.jpg" to jpg2,
            "p001.jpg" to jpg1,
            "sub/p003.gif" to byteArrayOf(7, 8),
            "notes.txt" to byteArrayOf(9),
        )
        val zipBytes = src.createZipFromTree()
        return ArchiveReader.open(zipBytes.asMemoryVfsFile("test.cbz"))
    }

    @Test
    fun listsImageEntriesOnly() = runTest {
        val entries = sampleZip().imageEntries()
        assertEquals(3, entries.size)
        assertTrue(entries.none { it.endsWith("notes.txt") })
        assertTrue(entries.any { it.endsWith("p001.jpg") })
        assertTrue(entries.any { it.endsWith("sub/p003.gif") })
    }

    @Test
    fun readsEntryBytes() = runTest {
        val reader = sampleZip()
        val path = reader.imageEntries().first { it.endsWith("p001.jpg") }
        assertEquals(jpg1.toList(), reader.readEntry(path).toList())
    }

    @Test
    fun rejectsNonZip() = runTest {
        val fake = byteArrayOf(1, 2, 3, 4).asMemoryVfsFile("fake.cbz")
        assertFailsWith<UnknownFormatException> {
            ArchiveReader.open(fake)
        }
    }

    @Test
    fun isImageName() {
        assertTrue(FormatDetector.isImageName("cover.JPEG"))
        assertTrue(FormatDetector.isImageName("p01.webp"))
        assertEquals(false, FormatDetector.isImageName("ComicInfo.xml"))
    }
}
