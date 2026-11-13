package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.db.ReaderDatabase
import data.localimport.LocalSourceFileSystem
import data.localimport.LocalSourceScanner
import korlibs.io.file.std.MemoryVfsMix
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 本地库扫描入库单测（desktop/JVM，真实 SQLDelight + 内存 Vfs）。
 * 锁死：目录即书（`local:` 行）、标题回退目录名、首扫时间保留、消失清理、异源行不动。
 */
class LocalLibrarySyncTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private fun seed(vararg pairs: Pair<String, ByteArray>): LocalSourceFileSystem =
        LocalSourceFileSystem(MemoryVfsMix(*pairs))

    private fun syncer(
        fs: LocalSourceFileSystem,
        db: ReaderDatabase,
        now: Long = 100L,
    ): Pair<LocalLibrarySync, LocalBookRepository> {
        val books = createLocalBookRepository(db)
        return LocalLibrarySync(LocalSourceScanner(fs), books) { now } to books
    }

    @Test
    fun emptyLibrarySyncsNothing() = runTest {
        val db = openDb()
        val (sync, books) = syncer(seed(), db)
        assertEquals(LocalLibrarySyncResult(0, 0), sync.sync())
        assertTrue(books.list().isEmpty())
    }

    @Test
    fun directoriesBecomeBooksAndResyncPreservesImportedAt() = runTest {
        val db = openDb()
        val fs = seed(
            "M1/ch01/p1.jpg" to ByteArray(4),
            "M2/ch01/p1.jpg" to ByteArray(4),
        )
        val (sync, books) = syncer(fs, db, now = 100L)
        assertEquals(LocalLibrarySyncResult(2, 0), sync.sync())
        assertEquals(listOf("local:M1", "local:M2"), books.list().map { it.id }.sorted())
        assertEquals("M1", books.find("local:M1")?.title)
        // 二次扫描不刷新首扫时间。
        val (sync2, _) = syncer(fs, db, now = 999L)
        assertEquals(LocalLibrarySyncResult(2, 0), sync2.sync())
        assertEquals(100L, books.find("local:M1")?.importedAtEpochMs)
    }

    @Test
    fun vanishedDirectoriesArePrunedAndForeignRowsKept() = runTest {
        val db = openDb()
        val books = createLocalBookRepository(db)
        val (sync, _) = syncer(
            seed("M1/ch01/p1.jpg" to ByteArray(4)),
            db,
        )
        sync.sync()
        // 注入异源行与将消失的行。
        books.upsert(
            core.model.LocalBook("other:x", "x", "txt", "异源", null, 1L),
        )
        assertEquals(2, books.list().size)
        // 目录消失后同步清理本地行，异源行保留。
        val (sync2, _) = syncer(seed(), db)
        assertEquals(LocalLibrarySyncResult(0, 1), sync2.sync())
        assertEquals(listOf("other:x"), books.list().map { it.id })
    }
}
