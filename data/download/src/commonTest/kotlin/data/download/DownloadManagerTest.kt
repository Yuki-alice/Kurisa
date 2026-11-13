package data.download

import core.model.DownloadStatus
import core.model.DownloadTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 下载编排单测：队列 / 优先级 / 分片落盘 / 暂停续传 / 取消 / 失败 / 重启恢复 /
 * BE-B7 并发与乱序完成。全部内存实现，不触网络、不写磁盘（C1）；
 * 用 runPending() 确定性驱动队列，不依赖测试调度器的虚拟时间推进（并发用例除外）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    /** 可制造乱序 / 限流的抓取器：delay 走虚拟时间，gate 用于暂停竞态。 */
    private class DelayFetcher(
        private val delayFor: (String) -> Long = { 1L },
        private val tracker: PeakTracker? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ByteFetcher {
        override suspend fun fetch(
            url: String,
            domains: Set<String>,
            fromByte: Long,
            headers: Map<String, String>,
        ): ByteFetcher.FetchResult {
            tracker?.enter()
            if (gate != null && url.endsWith("0.jpg")) gate.await()
            delay(delayFor(url))
            tracker?.leave()
            val body = url.encodeToByteArray()
            return ByteFetcher.FetchResult(body, body.size.toLong(), false)
        }
    }

    private class PeakTracker {
        private val lock = Mutex()
        private var current = 0
        var peak = 0
            private set

        suspend fun enter() = lock.withLock {
            current++
            if (current > peak) peak = current
        }

        suspend fun leave() = lock.withLock { current-- }
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
        assertEquals(2, sink.completedParts("low").size)
        assertEquals(1, sink.completedParts("high").size)
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
        assertEquals(0, sink.completedParts("t").size)
        assertTrue(seen.isEmpty())

        manager.resume("t")
        manager.runPending()
        assertEquals(DownloadStatus.DONE, store.observeTasks().first().single().status)
        assertEquals(1, sink.completedParts("t").size)
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
        assertEquals(3, sink.completedParts("t").size)
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
        assertEquals(1, sink.completedParts("t").size)

        manager.cancel("t")
        manager.runPending()
        assertTrue(store.observeTasks().first().isEmpty())
        assertEquals(0, sink.completedParts("t").size)
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

    @Test
    fun outOfOrderCompletionKeepsEveryPart() = runTest {
        val sink = InMemoryDownloadSink()
        val urls = (0 until 6).map { "https://a.example/$it.jpg" }
        val manager = DownloadManager(
            store = InMemoryDownloadTaskStore(),
            // 第 0 片最慢：并发下必然乱序完成。
            fetcher = DelayFetcher(delayFor = { if (it.endsWith("0.jpg")) 500L else 1L }),
            sink = sink,
            resolveUrls = { urls },
            concurrency = 4,
            scope = backgroundScope,
        )
        manager.enqueue(task("t"))
        manager.runPending()

        assertEquals((0 until 6).toSet(), sink.completedParts("t"))
        assertEquals(DownloadStatus.DONE, manager.tasks.value.single().status)
        assertEquals(
            urls.sumOf { it.encodeToByteArray().size.toLong() },
            manager.tasks.value.single().done,
        )
    }

    @Test
    fun sparseExistingPartsAreNotRefetched() = runTest {
        val sink = InMemoryDownloadSink()
        // 稀疏断点：1、3 已落盘，0、2 缺失（旧实现按数量 skip=2 会漏掉 0）。
        sink.append("t", 1, "old-1".encodeToByteArray())
        sink.append("t", 3, "old-3".encodeToByteArray())
        val urls = (0 until 4).map { "https://a.example/$it.jpg" }
        val seen = MutableStateFlow(emptyList<String>())
        val manager = DownloadManager(
            store = InMemoryDownloadTaskStore(),
            fetcher = object : ByteFetcher {
                override suspend fun fetch(
                    url: String,
                    domains: Set<String>,
                    fromByte: Long,
                    headers: Map<String, String>,
                ): ByteFetcher.FetchResult {
                    seen.update { it + url }
                    val body = url.encodeToByteArray()
                    return ByteFetcher.FetchResult(body, body.size.toLong(), false)
                }
            },
            sink = sink,
            resolveUrls = { urls },
            concurrency = 4,
            scope = backgroundScope,
        )
        manager.enqueue(task("t"))
        manager.runPending()

        assertEquals(setOf(urls[0], urls[2]), seen.value.toSet())
        assertEquals(setOf(0, 1, 2, 3), sink.completedParts("t"))
        assertEquals("old-1", sink.bytesOf("t", 1)?.decodeToString())
        assertEquals("old-3", sink.bytesOf("t", 3)?.decodeToString())
    }

    @Test
    fun concurrencyIsBounded() = runTest {
        val tracker = PeakTracker()
        val urls = (0 until 8).map { "https://a.example/$it.jpg" }
        val manager = DownloadManager(
            store = InMemoryDownloadTaskStore(),
            fetcher = DelayFetcher(delayFor = { 50L }, tracker = tracker),
            sink = InMemoryDownloadSink(),
            resolveUrls = { urls },
            concurrency = 2,
            scope = backgroundScope,
        )
        manager.enqueue(task("t"))
        manager.runPending()

        assertEquals(2, tracker.peak)
        assertEquals(DownloadStatus.DONE, manager.tasks.value.single().status)
    }

    @Test
    fun pauseDuringFlightKeepsPausedStateAndResumesWithoutGaps() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = InMemoryDownloadTaskStore()
        val sink = InMemoryDownloadSink()
        val urls = (0 until 4).map { "https://a.example/$it.jpg" }
        val manager = DownloadManager(
            store = store,
            fetcher = DelayFetcher(gate = gate),
            sink = sink,
            resolveUrls = { urls },
            concurrency = 4,
            scope = this,
        )
        manager.enqueue(task("t"))
        runCurrent()
        advanceTimeBy(2) // 让除第 0 片外的分片完成落盘；第 0 片仍挂在 gate
        manager.pause("t")
        gate.complete(Unit)
        advanceUntilIdle()

        // 在途分片落盘后不得把 PAUSED 覆盖成 RUNNING，也不得写 DONE。
        assertEquals(DownloadStatus.PAUSED, store.observeTasks().first().single().status)

        manager.resume("t")
        manager.runPending()
        assertEquals(DownloadStatus.DONE, store.observeTasks().first().single().status)
        assertEquals((0 until 4).toSet(), sink.completedParts("t"))
    }
}
