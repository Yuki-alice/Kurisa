package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.db.ReaderDatabase
import data.localimport.LocalSourceFileSystem
import korlibs.io.file.std.MemoryVfsMix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 本地书导入单测（desktop/JVM，内存 Vfs + 真实 SQLDelight）。
 * 锁死：落盘路径规则、格式白名单、同名幂等、首扫时间保留。
 */
class LocalBookImportTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private fun importer(
        fs: LocalSourceFileSystem,
        db: ReaderDatabase,
        now: Long = 7L,
    ): LocalBookImporter {
        val books = createLocalBookRepository(db)
        return LocalBookImporter(fs, books, { now })
    }

    @Test
    fun importWritesFilesAndRows() = runTest {
        val db = openDb()
        val fs = LocalSourceFileSystem(MemoryVfsMix())
        val imp = importer(fs, db)
        val result = imp.import(
            listOf(
                "My Book.epub" to ByteArray(16),
                "notes.txt" to "第一章\n正文。".encodeToByteArray(),
                "evil.exe" to ByteArray(8),
            ),
        )
        assertEquals(2, result.imported.size)
        assertEquals(listOf("evil.exe"), result.skipped)
        // 落盘路径：books/<净书名>/<原文件名>。
        assertTrue(fs.readBytes("My Book/My Book.epub").size == 16)
        assertEquals("txt", result.imported.first { it.id == "local:notes" }.format)
        // 同名再次导入幂等（行数不变，首扫时间保留）。
        val again = importer(fs, db, now = 99L).import(listOf("notes.txt" to ByteArray(4)))
        assertEquals(1, again.imported.size)
        assertEquals(7L, again.imported.single().importedAtEpochMs)
    }

    @Test
    fun removeDeletesDirAndRow() = runTest {
        val db = openDb()
        val fs = LocalSourceFileSystem(MemoryVfsMix())
        val imp = importer(fs, db)
        imp.import(listOf("gone.epub" to ByteArray(4)))
        assertTrue(imp.remove("local:gone"))
        assertTrue(runCatching { fs.readBytes("gone/gone.epub") }.isFailure)
        assertEquals(null, createLocalBookRepository(db).find("local:gone"))
        // 重复删除幂等。
        assertEquals(true, imp.remove("local:gone"))
    }

    @Test
    fun sanitizeStripsPathsAndControls() = runTest {
        val db = openDb()
        val fs = LocalSourceFileSystem(MemoryVfsMix())
        val imp = importer(fs, db)
        val result = imp.import(listOf("../evil.txt" to ByteArray(2)))
        assertEquals(1, result.imported.size)
        assertTrue(result.imported.single().id.startsWith("local:"))
        assertTrue(!result.imported.single().id.contains(".."))
    }
}
