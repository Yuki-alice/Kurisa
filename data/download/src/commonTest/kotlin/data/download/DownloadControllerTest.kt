package data.download

import core.model.DownloadStatus
import core.model.DownloadTask
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadControllerTest {

    private fun task(id: String, priority: Int) = DownloadTask(
        id = id, workId = "w", chapterId = "c",
        status = DownloadStatus.QUEUED, priority = priority,
        createdAtEpochMs = 1,
    )

    @Test
    fun priorityOrder() = runTest {
        val c = InMemoryDownloadController()
        c.enqueue(task("low", 0))
        c.enqueue(task("high", 10))
        assertEquals(listOf("high", "low"), c.tasks.value.map { it.id })
    }

    @Test
    fun pauseResumeCancel() = runTest {
        val c = InMemoryDownloadController()
        c.enqueue(task("a", 0))
        c.pause("a")
        assertEquals(DownloadStatus.PAUSED, c.tasks.value.single().status)
        c.resume("a")
        assertEquals(DownloadStatus.QUEUED, c.tasks.value.single().status)
        c.cancel("a")
        assertEquals(0, c.tasks.value.size)
    }
}
