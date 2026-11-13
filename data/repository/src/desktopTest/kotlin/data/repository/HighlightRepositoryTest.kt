package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.model.Highlight
import data.db.ReaderDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 划线仓库单测（desktop/JVM，真实 SQLDelight in-memory）。
 * 语义锁死：按章节列取（起点排序）、确定性 id 替换、按 id 删除。
 */
class HighlightRepositoryTest {

    private fun openRepo(): HighlightRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return createHighlightRepository(ReaderDatabase(driver))
    }

    private fun sample(id: String = "w:c1:3-9", start: Int = 3, end: Int = 9) = Highlight(
        id = id,
        workId = "w",
        chapterId = "c1",
        start = start,
        end = end,
        excerpt = "正文片段",
        createdAtEpochMs = 1_760_000_000_000L,
    )

    @Test
    fun listUpsertAndRemove() = runTest {
        val repo = openRepo()
        assertTrue(repo.list("w", "c1").isEmpty())
        repo.upsert(sample())
        repo.upsert(sample("w:c1:20-30", 20, 30))
        assertEquals(listOf("w:c1:3-9", "w:c1:20-30"), repo.list("w", "c1").map { it.id })
        // 同 id 再次 upsert 为替换。
        repo.upsert(sample().copy(end = 12))
        assertEquals(12, repo.list("w", "c1").single { it.id == "w:c1:3-9" }.end)
        repo.remove("w:c1:3-9")
        assertEquals(listOf("w:c1:20-30"), repo.list("w", "c1").map { it.id })
    }

    @Test
    fun noteRoundTripsThroughUpsert() = runTest {
        val repo = openRepo()
        repo.upsert(sample().copy(note = "我的想法"))
        assertEquals("我的想法", repo.list("w", "c1").single().note)
        // 空串视为清除（VM 层归一化；仓库按值存储）。
        repo.upsert(sample().copy(note = null))
        assertEquals(null, repo.list("w", "c1").single().note)
    }

    @Test
    fun listIsScopedByChapter() = runTest {
        val repo = openRepo()
        repo.upsert(sample())
        repo.upsert(sample("w:c2:0-5", 0, 5).copy(chapterId = "c2"))
        assertEquals(1, repo.list("w", "c1").size)
        assertEquals(1, repo.list("w", "c2").size)
        assertTrue(repo.list("w", "c9").isEmpty())
    }
}
