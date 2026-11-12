package data.localimport

import korlibs.io.file.std.MemoryVfsMix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSourceFileSystemTest {

    private suspend fun seed(): LocalSourceFileSystem {
        val mem = MemoryVfsMix(
            "mangaA/ch01/p001.jpg" to byteArrayOf(1, 2, 3),
            "mangaA/vol1.cbz" to byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            "notes.txt" to byteArrayOf(9),
        )
        mem[".hidden"].mkdir()
        return LocalSourceFileSystem(mem)
    }

    @Test
    fun baseEntriesFiltersHidden() = runTest {
        val names = seed().baseEntries().map { it.name }.toSet()
        assertEquals(setOf("mangaA", "notes.txt"), names)
    }

    @Test
    fun mangaEntriesListChapters() = runTest {
        val entries = seed().mangaEntries("mangaA").associateBy { it.name }
        assertEquals(setOf("ch01", "vol1.cbz"), entries.keys)
        assertTrue(entries.getValue("ch01").isDirectory)
        assertEquals(false, entries.getValue("vol1.cbz").isDirectory)
    }

    @Test
    fun readBytesAndHeader() = runTest {
        val fs = seed()
        assertEquals(listOf<Byte>(1, 2, 3), fs.readBytes("mangaA/ch01/p001.jpg").toList())
        assertEquals(
            listOf<Byte>(0x50, 0x4B),
            fs.readHeader("mangaA/vol1.cbz", size = 2).toList(),
        )
    }
}
