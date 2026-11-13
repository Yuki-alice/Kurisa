package source.bridge

import core.reader.ChapterRef
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BE-B2 门禁：在线写透、离线回退、无缓存重抛、写失败不破坏读取。 */
class BridgeChapterProviderCacheTest {

    class FakeCalls(
        var details: String = "",
        var fail: Throwable? = null,
    ) : SourceCalls {
        override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int) = ""
        override suspend fun defaultSearchOptions() = "[]"
        override suspend fun loadWorkDetails(workId: String, kind: ContentKind): String =
            fail?.let { throw it } ?: details
        override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind) = ""
        override suspend fun invoke(path: String, argsJson: String) = ""
    }

    class FakePort(
        var stored: List<ChapterRef>? = null,
        var failOnSave: Throwable? = null,
    ) : ChapterCachePort {
        val saves = mutableListOf<Triple<String, List<ChapterRef>, Long>>()
        override suspend fun saveChapters(
            workId: String,
            kind: ContentKind,
            chapters: List<ChapterRef>,
            updatedAtEpochMs: Long,
        ) {
            failOnSave?.let { throw it }
            saves += Triple(workId, chapters, updatedAtEpochMs)
            stored = chapters
        }

        override suspend fun loadChapters(workId: String) = stored
    }

    private fun mangaJson(vararg ids: String): String {
        val entries = ids.joinToString(",") { id -> """"$id":"$id"""" }
        return """{"chapters":{$entries}}"""
    }

    @Test
    fun onlineSuccessWritesThrough() = runTest {
        val port = FakePort()
        val p = BridgeChapterProvider(
            FakeCalls(details = mangaJson("c1", "c2")),
            ContentKind.MANGA,
            chapterCache = port,
            nowMs = { 4242L },
        )
        val chapters = p.chapters("w")
        assertEquals(listOf("c1", "c2"), chapters.map { it.id })
        assertEquals(1, port.saves.size)
        assertEquals("w", port.saves[0].first)
        assertEquals(4242L, port.saves[0].third)
    }

    @Test
    fun offlineFallsBackToCache() = runTest {
        val port = FakePort(stored = listOf(ChapterRef("c9", "缓存话", 1)))
        val p = BridgeChapterProvider(
            FakeCalls(fail = IllegalStateException("net down")),
            ContentKind.MANGA,
            chapterCache = port,
        )
        assertEquals(listOf("c9"), p.chapters("w").map { it.id })
        assertTrue(port.saves.isEmpty())
    }

    @Test
    fun offlineWithoutCacheRethrows() = runTest {
        val p = BridgeChapterProvider(
            FakeCalls(fail = IllegalStateException("net down")),
            ContentKind.MANGA,
            chapterCache = FakePort(stored = null),
        )
        assertFailsWith<IllegalStateException> { p.chapters("w") }
    }

    @Test
    fun saveFailureStillReturnsRemoteAndReportsHook() = runTest {
        val port = FakePort(failOnSave = IllegalStateException("db down"))
        var hooked: Throwable? = null
        val p = BridgeChapterProvider(
            FakeCalls(details = mangaJson("c1")),
            ContentKind.MANGA,
            chapterCache = port,
            onChapterCacheError = { hooked = it },
        )
        assertEquals(listOf("c1"), p.chapters("w").map { it.id })
        assertTrue(hooked is IllegalStateException)
    }

    @Test
    fun noCacheKeepsPlainOnlineBehavior() = runTest {
        val p = BridgeChapterProvider(FakeCalls(details = mangaJson("c1")), ContentKind.MANGA)
        assertEquals(listOf("c1"), p.chapters("w").map { it.id })
    }
}