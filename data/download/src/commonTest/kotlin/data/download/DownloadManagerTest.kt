package data.download

import core.model.DownloadStatus
import core.model.DownloadTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 下载编排单测：队列 / 优先级 / 分片落盘 / 暂停续传 / 取消 / 失败 / 重启恢复。
 * 全部内存实现，不触网络、不写磁盘（C1）；用 runPending() 确定性驱动队列，
 * 不依赖测试调度器的虚拟时间推进。
 */
class DownloadManagerTest {

    private class FakeFetcher(
        private val body: ByteArray = "img".encodeToByteArray(),
        private val seen: MutableList<String>,
    ) : ByteFetcher {
        override suspend fun fetch(
            url: String,
            domains: Set<String>,
            fromByte: Long,
            headers: Map<String, String>,
        ): ByteFetcher.FetchResult {
            seen += url
            return ByteFetcher.FetchResult(body, body.size.toLong(), false)
        }
    }

    private fun task(id: String, priority: Int = 0, created: Long = 1L) = DownloadTask(
        id = id,
        workId = "w",
        chapterId = "c",
        priority = priority,
        createdAtEpochMs = created,
    )

    @Test
    fun enqueueDownloadsAllPartsAndHonorsPriority() = runTest {
        val store = InMemoryDownloadTaskStore()
        val sink = InMemoryDownloadSink()
        val seen = mutableListOf<String>()
        val urls = mapOf(
            "low" to listOf("https://a.example/1.jpg", "https://a.example/2.jpg"),
            "high" to listOf("https://b.example/1.jpg"),
        )
        val manager = DownloadManager(
            store = store,
            fetcher = FakeFetcher(seen = seen),
            sink = sink,
            resolveUrls = { t -> urls[t.id].orEmpty() },
            domains = { setOf("a.example", "b.example") },
            scope = backgroundScope,
        )

        manager.enqueue(task("low", priority = 0, created = 1))
        manager.enqueue(task("high", priority = 5, created = 2))
        manager.runPending()

        // 高优先级先跑，且跨级都跑完。
        assertEquals("https://b.example/1.jpg", seen.first())
        assertEquals(3, seen.size)
        assertTrue(manager.tasks.value.all { it.status == DownloadStatus.DONE })
        assertEquals(2, sink.completedParts("low"))
        assertEquals(1, sink.completedParts("high"))
        assertNotNull(sink.bytesOf("low", 0))
        assertNotNull(sink.bytesOf("low", 1))
        assertEquals(6L, manager.tasks.value.first { it.id == "low" }.done) // 2 片 × 3 字节
    }

    @Test
    fun pausePreventsExecutionAndResumeCompletes() = runTest {
        val store = InMemoryDownloadTaskStore()
        val sink = InMemoryDownloadSink()
        val seen = mutableListOf<String>()
        val manager = DownloadManager(
            store = store,
            fetcher = FakeFetcher(seen = seen),
            sink = sink,
            resolveUrls = { listOf("https://a.example/1.jpg") },
            scope = backgroundScope,
        )

        manager.enqueue(task("t"))
        manager.pause("t")
        manager.runPending() // PAUSED 不应被拾取
        assertEquals(DownloadStatus.PAUSED, store.observeTasks().first().single().status)
        assertEquals(0, sink.completedParts("t"))
        assertTrue(seen.isEmpty())

        manager.resume("t")
        manager.runPending()
        assertEquals(DownloadStatus.DONE, store.observeTasks().first().single().status)
        assertEquals(1, sink.completedParts("t"))
    }

    @Test
    fun resumeAfterRestartSkipsAlreadyPersistedParts() = runTest {
        val store = InMemoryDownloadTaskStore()
        val sink = InMemoryDownloadSink()
        // 模拟进程被杀：已有第 0 片落盘，DB 里还停在 RUNNING。
        sink.append("t", 0, "old".encodeToByteArray())
        store.upsert(task("t").copy(status = DownloadStatus.RUNNING, done = 3L))
        val seen = mutableListOf<String>()
        val manager = DownloadManager(
            store = store,
            fetcher = FakeFetcher(seen = seen),
            sink = sink,
            resolveUrls = {
                listOf(
                    "https://a.example/1.jpg",
                    "https://a.example/2.jpg",
                    "https://a.example/3.jpg",
                )
            },
            scope = backgroundScope,
        )
        manager.runPending()

        // 只抓后两片；第 0 片保持旧字节。
        assertEquals(2, seen.size)
        assertEquals(listOf("https://a.example/2.jpg", "https://a.example/3.jpg"), seen)
        assertEquals(3, sink.completedParts("t"))
        assertEquals("old", sink.bytesOf("t", 0)?.decodeToString())
        assertEquals(DownloadStatus.DONE, store.observeTasks().first().single().status)
    }

    @Test
    fun cancelRemovesTaskAndParts() = runTest {
        val store = InMemoryDownloadTaskStore()
        val sink = InMemoryDownloadSink()
        val manager = DownloadManager(
            store = store,
            fetcher = FakeFetcher(seen = mutableListOf()),
            sink = sink,
            resolveUrls = { listOf("https://a.example/1.jpg") },
            scope = backgroundScope,
        )
        manager.enqueue(task("t"))
        manager.runPending()
        assertEquals(1, sink.completedParts("t"))

        manager.cancel("t")
        manager.runPending()
        assertTrue(store.observeTasks().first().isEmpty())
        assertEquals(0, sink.completedParts("t"))
        assertNull(sink.bytesOf("t", 0))
    }

    @Test
    fun failuresAreExplicitNotSilent() = runTest {
        val store = InMemoryDownloadTaskStore()
        val empty = DownloadManager(
            store = InMemoryDownloadTaskStore(),
            fetcher = FakeFetcher(seen = mutableListOf()),
            sink = InMemoryDownloadSink(),
            resolveUrls = { emptyList() },
            scope = backgroundScope,
        )
        empty.enqueue(task("no-content"))
        empty.runPending()
        assertEquals(DownloadStatus.FAILED, empty.tasks.value.single().status)
        assertTrue(empty.tasks.value.single().error!!.contains("无可下载内容"))

        val broken = DownloadManager(
            store = store,
            fetcher = FakeFetcher(seen = mutableListOf()),
            sink = InMemoryDownloadSink(),
            resolveUrls = { error("resolve-boom") },
            scope = backgroundScope,
        )
        broken.enqueue(task("broken"))
        broken.runPending()
        assertEquals(DownloadStatus.FAILED, broken.tasks.value.single().status)
        assertTrue(broken.tasks.value.single().error!!.contains("resolve-boom"))
    }
}
