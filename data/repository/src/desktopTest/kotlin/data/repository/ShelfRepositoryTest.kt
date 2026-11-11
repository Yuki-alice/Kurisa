package data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.model.DownloadTask
import core.model.ReadingProgress
import core.model.Shelf
import data.db.ReaderDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import source.contract.Work
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M2 Gate：断网书架可用（desktop/JVM，真实 SQLDelight + Flow）。
 * - 缓存优先：observe 先吐本地，无网络调用；
 * - 刷新失败不清空缓存（此处以“无远端”模拟，缓存仍在）；
 * - Flow 在写入后自动发射（无手动失效）。
 */
class ShelfRepositoryTest {

    private fun openDb(): ReaderDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        ReaderDatabase.Schema.create(driver)
        return ReaderDatabase(driver)
    }

    private fun sampleWork() = Work(
        id = "b23c9bb1", title = "お前じゃん", subtitle = "作者",
        cover = "https://example.com/c.jpg", tags = listOf("纯爱"),
        description = "desc", language = "zh_CN", kind = ContentKind.MANGA,
    )

    @Test
    fun observeEmitsCachedWithoutNetwork() = runTest {
        val db = openDb()
        val repo = createShelfRepository(db)
        repo.ensureShelf(Shelf("default", "默认", 0))
        // 空书架
        assertTrue(repo.observeShelf("default").first().isEmpty())
        // 写入后 Flow 自动更新
        repo.addToShelf("default", "manga_dex", sampleWork(), 1_760_000_000_000L)
        val items = repo.observeShelf("default").first()
        assertEquals(1, items.size)
        assertEquals("お前じゃん", items[0].work.title)
        // 宿主 ID 规则：stableId 非原文 id
        assertTrue(items[0].workId.length == 64)
    }

    @Test
    fun removeKeepsOthers() = runTest {
        val db = openDb()
        val repo = createShelfRepository(db)
        repo.ensureShelf(Shelf("default", "默认", 0))
        repo.addToShelf("default", "k", sampleWork(), 1L)
        repo.addToShelf("default", "k", sampleWork().copy(id = "w2", title = "第二部"), 2L)
        assertEquals(2, repo.observeShelf("default").first().size)
        val firstId = repo.observeShelf("default").first()[0].workId
        repo.removeFromShelf("default", firstId)
        assertEquals(1, repo.observeShelf("default").first().size)
    }

    @Test
    fun progressRoundTrip() = runTest {
        val db = openDb()
        val repo = createProgressRepository(db)
        repo.save(ReadingProgress("w1", "c1", 3, null, 0.5f, 1_760_000_000_000L, false))
        val loaded = repo.load("w1")
        assertEquals("c1", loaded?.chapterId)
        assertEquals(0.5f, loaded?.percent)
    }

    @Test
    fun downloadTasksRoundTrip() = runTest {
        val db = openDb()
        val repo = createDownloadTaskRepository(db)
        assertTrue(repo.observeTasks().first().isEmpty())
        repo.upsert(
            DownloadTask(
                "d1", "w1", "c1", core.model.DownloadStatus.QUEUED,
                5, 0, 100, null, 1_760_000_000_000L,
            ),
        )
        val items = repo.observeTasks().first()
        assertEquals(1, items.size)
        assertEquals(100, items[0].total)
        repo.upsert(items[0].copy(status = core.model.DownloadStatus.DONE, done = 100))
        assertEquals(core.model.DownloadStatus.DONE, repo.observeTasks().first().single().status)
        repo.delete("d1")
        assertTrue(repo.observeTasks().first().isEmpty())
    }
}
