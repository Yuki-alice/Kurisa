package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.model.Bookmark
import data.db.ReaderDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 书签仓库单测（desktop/JVM，真实 SQLDelight in-memory）。
 * 语义锁：每章节一条（确定性 id 替换）、按作品列取、按章节删。
 */
class BookmarkRepositoryTest {

    private fun openRepo(): BookmarkRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return createBookmarkRepository(ReaderDatabase(driver))
    }

    private fun sample(chapter: String = "c1", position: Int = 123) = Bookmark(
        id = "w:$chapter",
        workId = "w",
        chapterId = chapter,
        position = position,
        excerpt = "正文片段",
        createdAtEpochMs = 1_760_000_000_000L,
    )

    @Test
    fun upsertFindAndRemoveByChapter() = runTest {
        val repo = openRepo()
        assertNull(repo.findByChapter("w", "c1"))
        repo.upsert(sample())
        assertEquals(123, repo.findByChapter("w", "c1")?.position)
        assertEquals("正文片段", repo.findByChapter("w", "c1")?.excerpt)
        // 同章节再次 upsert 为替换（仍一条）
        repo.upsert(sample(position = 456))
        assertEquals(456, repo.findByChapter("w", "c1")?.position)
        assertEquals(1, repo.listForWork("w").size)
        repo.removeByChapter("w", "c1")
        assertNull(repo.findByChapter("w", "c1"))
        assertTrue(repo.listForWork("w").isEmpty())
    }

    @Test
    fun listIsScopedByWork() = runTest {
        val repo = openRepo()
        repo.upsert(sample("c1"))
        repo.upsert(sample("c2").copy(id = "w:c2"))
        repo.upsert(sample("c1").copy(workId = "other", id = "other:c1"))
        assertEquals(2, repo.listForWork("w").size)
        assertEquals(1, repo.listForWork("other").size)
    }
}
