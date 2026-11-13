package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.reader.ChapterRef
import data.db.ReaderDatabase
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B-4 门禁：last_read_at 写透 + 时长累计（kv）+ 续读 + 按日分组；不新增表/列。
 * 外键打开（与 ShelfRepositoryTest 同装置），覆盖 REPLACE 级联下章节不丢。
 */
class ReadingHistoryRepositoryTest {

    private val t0 = 1_760_000_000_000L

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    @Test
    fun recordWritesThroughAndAccumulates() = runTest {
        val db = openDb()
        val repo = createReadingHistoryRepository(db)
        var s = repo.record(ReadingEvent("w1", t0, 60_000L))
        assertEquals(60_000L, s.totalDurationMs)
        assertEquals(1, s.sessionCount)
        assertEquals(t0, s.lastReadAtEpochMs)
        assertEquals(t0, db.workQueries.selectWork("w1").executeAsOne().last_read_at)
        s = repo.record(ReadingEvent("w1", t0 + 1_000L, 30_000L))
        assertEquals(90_000L, s.totalDurationMs)
        assertEquals(2, s.sessionCount)
        assertEquals(t0 + 1_000L, s.lastReadAtEpochMs)
    }

    @Test
    fun recordPreservesChaptersUnderForeignKeys() = runTest {
        val db = openDb()
        val chapters = createChapterCacheRepository(db)
        chapters.saveChapters("w1", ContentKind.MANGA, listOf(ChapterRef("c1", "第一话", 1)), t0)
        val repo = createReadingHistoryRepository(db)
        repo.record(ReadingEvent("w1", t0 + 5_000L, 10_000L))
        assertEquals(listOf("c1"), chapters.loadChapters("w1")?.map { it.id })
        assertEquals(t0 + 5_000L, db.workQueries.selectWork("w1").executeAsOne().last_read_at)
    }

    @Test
    fun recordSkipsStaleTimestampWrite() = runTest {
        val db = openDb()
        val repo = createReadingHistoryRepository(db)
        repo.record(ReadingEvent("w1", t0 + 5_000L, 10_000L))
        repo.record(ReadingEvent("w1", t0, 10_000L))
        assertEquals(t0 + 5_000L, db.workQueries.selectWork("w1").executeAsOne().last_read_at)
        // kv 累计不受影响（last 取 max）
        assertEquals(t0 + 5_000L, repo.stats("w1").lastReadAtEpochMs)
        assertEquals(20_000L, repo.stats("w1").totalDurationMs)
    }

    @Test
    fun rejectsNegativeDuration() = runTest {
        val repo = createReadingHistoryRepository(openDb())
        var failed = false
        runCatching { repo.record(ReadingEvent("w1", t0, -1L)) }.onFailure { failed = true }
        assertTrue(failed)
    }

    @Test
    fun recentOrdersByRecency() = runTest {
        val repo = createReadingHistoryRepository(openDb())
        repo.record(ReadingEvent("w1", t0, 1_000L))
        repo.record(ReadingEvent("w2", t0 + 1_000L, 1_000L))
        assertEquals(listOf("w2", "w1"), repo.recent(10).map { it.workId })
        repo.record(ReadingEvent("w1", t0 + 2_000L, 1_000L))
        assertEquals(listOf("w1", "w2"), repo.recent(10).map { it.workId })
        assertEquals(listOf("w1"), repo.recent(1).map { it.workId })
    }

    @Test
    fun historyGroupsByUtcDay() = runTest {
        val repo = createReadingHistoryRepository(openDb())
        val day = t0 / MILLIS_PER_DAY
        repo.record(ReadingEvent("w1", t0, 60_000L))
        repo.record(ReadingEvent("w2", (day + 1) * MILLIS_PER_DAY + 1_000L, 30_000L))
        val days = repo.history(days = 30, nowMs = (day + 1) * MILLIS_PER_DAY + 2_000L)
        assertEquals(2, days.size)
        // 新的天在前
        assertEquals((day + 1) * MILLIS_PER_DAY, days[0].dayStartEpochMs)
        assertEquals(listOf("w2"), days[0].workIds)
        assertEquals(30_000L, days[0].totalDurationMs)
        assertEquals(day * MILLIS_PER_DAY, days[1].dayStartEpochMs)
        assertEquals(listOf("w1"), days[1].workIds)
    }

    @Test
    fun historySkipsEmptyDays() = runTest {
        val repo = createReadingHistoryRepository(openDb())
        repo.record(ReadingEvent("w1", t0, 5_000L))
        val days = repo.history(days = 7, nowMs = t0 + 6 * MILLIS_PER_DAY)
        assertEquals(1, days.size)
        assertEquals(listOf("w1"), days[0].workIds)
    }

    @Test
    fun emptyHistoryAndStatsFallback() = runTest {
        val db = openDb()
        val repo = createReadingHistoryRepository(db)
        assertTrue(repo.history().isEmpty())
        assertTrue(repo.recent().isEmpty())
        val s = repo.stats("ghost")
        assertEquals(0L, s.totalDurationMs)
        assertEquals(0, s.sessionCount)
        assertNull(s.lastReadAtEpochMs)
    }

    @Test
    fun statsFallsBackToWorkRow() = runTest {
        val db = openDb()
        db.workQueries.upsertWork(
            id = "w1",
            source_key = "src.example",
            source_work_id = "ext-1",
            kind = ContentKind.MANGA.serial,
            title = "真实标题",
            subtitle = null,
            cover = null,
            description = null,
            tags_json = "[]",
            stars = null,
            language = null,
            url = null,
            added_at = 500L,
            last_read_at = 700L,
        )
        val repo = createReadingHistoryRepository(db)
        val s = repo.stats("w1")
        assertEquals(0L, s.totalDurationMs)
        assertEquals(700L, s.lastReadAtEpochMs)
    }
}
