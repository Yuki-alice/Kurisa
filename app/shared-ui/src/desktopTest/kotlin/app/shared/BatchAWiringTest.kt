package app.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import core.model.DownloadStatus
import core.model.DownloadTask
import data.db.ReaderDatabase
import data.network.CookieStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 批次 A 生产接线的回归测试（desktopTest，in-memory DB + MockEngine）。
 *
 * 数据层自身的闭环由 `DownloadManagerTest` / `ShelfBindingsTest` /
 * `SourceRepoUpdatesTest` 覆盖；本文件只锁 App 装配层新增的胶水：
 * 发现出口无源态、订阅坏索引不崩、下载任务 → 下载页展示行的状态标志。
 * 索引与源 key 一律 `example.*`（C1），不触发真实网络。
 */
class BatchAWiringTest {

    private fun catalog(): SourceCatalog {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ReaderDatabase.Schema.create(driver)
        return createSourceCatalog(
            db = ReaderDatabase(driver),
            httpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
            cookies = CookieStore(),
            loadScript = { error("无可用源时不应装配引擎") },
            nowMs = { 7L },
        )
    }

    @Test
    fun exploreWithoutInstalledSourceReturnsExplicitError() = runTest {
        val page = catalog().explore(sourceKey = "com.example.missing")
        assertTrue(page.works.isEmpty())
        assertEquals(listOf("无可用源"), page.errors)
    }

    @Test
    fun malformedRepoIndexIsReportedNotThrown() = runTest {
        val result = catalog().refreshRepoUpdates { "{ 这不是 JSON" }
        assertNotNull(result.error)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun downloadTaskUiMapsPauseAndResumeFlags() {
        fun task(status: DownloadStatus) = DownloadTask(
            id = "task-1",
            workId = "w1",
            chapterId = "c1",
            status = status,
            createdAtEpochMs = 7L,
        )
        val running = task(DownloadStatus.RUNNING).toUi()
        assertTrue(running.canPause)
        assertFalse(running.canResume)
        assertEquals("RUNNING", running.statusLabel)

        val paused = task(DownloadStatus.PAUSED).toUi()
        assertFalse(paused.canPause)
        assertTrue(paused.canResume)

        val done = task(DownloadStatus.DONE).toUi()
        assertFalse(done.canPause)
        assertFalse(done.canResume)
    }
}
