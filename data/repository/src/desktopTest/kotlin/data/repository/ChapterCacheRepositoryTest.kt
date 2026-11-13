package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.reader.ChapterRef
import data.db.ReaderDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BE-B2 门禁：章节写透 chapter 表、离线可读、更新对比；不新增表/列。
 *（真实 SQLDelight in-memory 库，外键打开，与 ShelfRepositoryTest 同装置。）
 */
class ChapterCacheRepositoryTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private fun chapters(vararg ids: String) =
        ids.mapIndexed { i, id -> ChapterRef(id, "话" + (i + 1), i + 1) }

    @Test
    fun saveLoadRoundTrip() = runTest {
        val repo = createChapterCacheRepository(openDb())
        assertNull(repo.loadChapters("w1"))
        repo.saveChapters("w1", ContentKind.MANGA, chapters("c1", "c2"), 1000L)
        val loaded = repo.loadChapters("w1")
        assertEquals(listOf("c1", "c2"), loaded?.map { it.id })
        assertEquals(listOf(1, 2), loaded?.map { it.ordinal })
    }

    @Test
    fun fullReplaceDropsRemovedChapters() = runTest {
        val repo = createChapterCacheRepository(openDb())
        repo.saveChapters("w1", ContentKind.MANGA, chapters("c1", "c2"), 1000L)
        repo.saveChapters("w1", ContentKind.MANGA, chapters("c2", "c3"), 2000L)
        assertEquals(listOf("c2", "c3"), repo.loadChapters("w1")?.map { it.id })
    }

    @Test
    fun compareDetectsAddedAndRemoved() = runTest {
        val repo = createChapterCacheRepository(openDb())
        repo.saveChapters("w1", ContentKind.MANGA, chapters("c1", "c2"), 1000L)
        val diff = repo.compare("w1", chapters("c2", "c3"))
        assertEquals(listOf("c3"), diff.added.map { it.id })
        assertEquals(listOf("c1"), diff.removedIds)
    }

    @Test
    fun saveComparedWritesAndReports() = runTest {
        val repo = createChapterCacheRepository(openDb())
        val diff = repo.saveCompared("w1", ContentKind.NOVEL, chapters("c1"), 1000L)
        assertEquals(listOf("c1"), diff.added.map { it.id })
        assertTrue(diff.removedIds.isEmpty())
        assertEquals(listOf("c1"), repo.loadChapters("w1")?.map { it.id })
    }

    @Test
    fun observeEmitsAfterSave() = runTest {
        val repo = createChapterCacheRepository(openDb())
        assertTrue(repo.observeChapters("w1").first().isEmpty())
        repo.saveChapters("w1", ContentKind.MANGA, chapters("c1"), 1000L)
        assertEquals(listOf("c1"), repo.observeChapters("w1").first().map { it.id })
    }

    @Test
    fun saveDoesNotClobberExistingWorkRow() = runTest {
        val db = openDb()
        db.workQueries.upsertWork(
            id = "w1",
            source_key = "src.example",
            source_work_id = "ext-1",
            kind = ContentKind.MANGA.serial,
            title = "真实标题",
            subtitle = "副",
            cover = null,
            description = "简介",
            tags_json = "[]",
            stars = null,
            language = "zh",
            url = null,
            added_at = 500L,
            last_read_at = 700L,
        )
        val repo = createChapterCacheRepository(db)
        repo.saveChapters("w1", ContentKind.MANGA, chapters("c1"), 1000L)
        val row = db.workQueries.selectWork("w1").executeAsOne()
        assertEquals("真实标题", row.title)
        assertEquals(700L, row.last_read_at)
        assertEquals(listOf("c1"), repo.loadChapters("w1")?.map { it.id })
    }
}
