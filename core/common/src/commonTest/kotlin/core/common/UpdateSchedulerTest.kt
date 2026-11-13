package core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BE-B3 门禁：更新轮询编排（纯逻辑 + 虚拟时间，无平台依赖、不用真实站点）。
 */
class UpdateSchedulerTest {

    class FakeSource(
        var keys: List<String> = emptyList(),
        var works: Map<String, List<String>> = emptyMap(),
        var local: Map<String, List<ChapterStamp>> = emptyMap(),
        var remote: Map<String, List<ChapterStamp>> = emptyMap(),
        var failKeys: Throwable? = null,
        var failWorks: Set<String> = emptySet(),
        var failRemote: Set<String> = emptySet(),
    ) : UpdateSource {
        private fun id(key: String, work: String) = key + "/" + work

        override suspend fun activeSourceKeys() = failKeys?.let { throw it } ?: keys

        override suspend fun cachedWorkIds(sourceKey: String): List<String> {
            if (sourceKey in failWorks) throw IllegalStateException("works down")
            return works[sourceKey] ?: emptyList()
        }

        override suspend fun localChapters(sourceKey: String, workId: String) =
            local[id(sourceKey, workId)] ?: emptyList()

        override suspend fun remoteChapters(sourceKey: String, workId: String): List<ChapterStamp> {
            if (id(sourceKey, workId) in failRemote) throw IllegalStateException("net down")
            return remote[id(sourceKey, workId)] ?: emptyList()
        }
    }

    private fun stamp(id: String, ts: Long = 1L) = ChapterStamp(id, "h" + id, ts)

    @Test
    fun detectsAddedAndRemoved() = runTest {
        val src = FakeSource(
            keys = listOf("b", "a"),
            works = mapOf("a" to listOf("w1"), "b" to listOf("w2")),
            local = mapOf("a/w1" to listOf(stamp("c1"), stamp("c2"))),
            remote = mapOf(
                "a/w1" to listOf(stamp("c2"), stamp("c3")),
                "b/w2" to listOf(stamp("c1")),
            ),
        )
        val scan = UpdateScheduler(src).scanOnce()
        assertEquals(2, scan.scannedWorks)
        assertEquals(2, scan.candidates.size)
        // 按 sourceKey/workId 排序，与输入顺序无关
        assertEquals("a", scan.candidates[0].sourceKey)
        assertEquals(listOf("c3"), scan.candidates[0].addedChapterIds)
        assertEquals(listOf("c1"), scan.candidates[0].removedChapterIds)
        assertEquals("b", scan.candidates[1].sourceKey)
        assertTrue(scan.errors.isEmpty())
    }

    @Test
    fun unchangedWorksProduceNoCandidates() = runTest {
        val src = FakeSource(
            keys = listOf("a"),
            works = mapOf("a" to listOf("w1")),
            local = mapOf("a/w1" to listOf(stamp("c1"))),
            remote = mapOf("a/w1" to listOf(stamp("c1", 999L))),
        )
        val scan = UpdateScheduler(src).scanOnce()
        assertEquals(1, scan.scannedWorks)
        assertTrue(scan.candidates.isEmpty())
        assertTrue(scan.errors.isEmpty())
    }

    @Test
    fun remoteFailureIsolatedPerWork() = runTest {
        val src = FakeSource(
            keys = listOf("a"),
            works = mapOf("a" to listOf("w1", "w2")),
            remote = mapOf("a/w2" to listOf(stamp("c9"))),
            failRemote = setOf("a/w1"),
        )
        val scan = UpdateScheduler(src).scanOnce()
        assertEquals(2, scan.scannedWorks)
        assertEquals(1, scan.candidates.size)
        assertEquals("w2", scan.candidates[0].workId)
        assertEquals(1, scan.errors.size)
        assertTrue(scan.errors[0].startsWith("a/w1:"))
    }

    @Test
    fun keysFailureYieldsEmptyScan() = runTest {
        val src = FakeSource(failKeys = IllegalStateException("db down"))
        val scan = UpdateScheduler(src).scanOnce()
        assertEquals(0, scan.scannedWorks)
        assertTrue(scan.candidates.isEmpty())
        assertEquals(1, scan.errors.size)
    }

    @Test
    fun emptySourcesScanEmpty() = runTest {
        val scan = UpdateScheduler(FakeSource()).scanOnce()
        assertEquals(0, scan.scannedWorks)
        assertTrue(scan.candidates.isEmpty())
        assertTrue(scan.errors.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun runLoopScansEachTickWithRealTrigger() = runTest {
        val src = FakeSource(
            keys = listOf("a"),
            works = mapOf("a" to listOf("w1")),
            remote = mapOf("a/w1" to listOf(stamp("c1"))),
        )
        val scans = mutableListOf<UpdateScan>()
        val scheduler = UpdateScheduler(src, IntervalUpdateTrigger(1000L))
        val job = launch { scheduler.run(scans::add) }
        // 本工程虚拟时间语义：恰到期不触发，需越过边界（见 CommonTest 注释）。
        advanceTimeBy(1005)
        assertEquals(1, scans.size)
        advanceTimeBy(1000)
        advanceTimeBy(5)
        assertEquals(2, scans.size)
        job.cancel()
    }

    @Test
    fun diffChaptersPure() {
        val (added, removed) = UpdateScheduler.diffChapters(
            listOf(stamp("c1"), stamp("c2")),
            listOf(stamp("c2"), stamp("c3")),
        )
        assertEquals(listOf("c3"), added)
        assertEquals(listOf("c1"), removed)
    }
}
