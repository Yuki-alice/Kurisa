package core.reader

import core.model.ChapterPayload
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeChapters(
    private val chapters: List<ChapterRef> = listOf(
        ChapterRef("c1", "第1话", 1),
        ChapterRef("c2", "第2话", 2),
    ),
    private val failChapters: Boolean = false,
    private val failPayload: Boolean = false,
) : ChapterProvider {
    override suspend fun chapters(workId: String): List<ChapterRef> {
        if (failChapters) throw IllegalStateException("net")
        return chapters
    }

    override suspend fun payload(workId: String, chapterId: String): ChapterPayload {
        if (failPayload) throw IllegalStateException("net")
        return ChapterPayload.Images(listOf("https://example.com/1.jpg", "https://example.com/2.jpg", "https://example.com/3.jpg"))
    }
}

class ReaderStateMachineTest {

    @Test
    fun idleToReading() {
        val m = ReaderStateMachine()
        assertEquals(ReaderState.Idle, m.state.value)
        m.resolve("w", "c")
        m.loading("w", "c")
        m.ready("w", "c", 10)
        m.pageTo(3)
        val s = m.state.value
        assertTrue(s is ReaderState.Reading)
        assertEquals(3, s.pageIndex)
    }

    @Test
    fun pageIgnoredOutsideReading() {
        val m = ReaderStateMachine()
        m.pageTo(5)
        assertEquals(ReaderState.Idle, m.state.value)
        m.fail("net")
        assertTrue(m.state.value is ReaderState.Error)
        m.reset()
        assertEquals(ReaderState.Idle, m.state.value)
    }

    @Test
    fun openChapterPipeline() = runTest {
        val m = ReaderStateMachine()
        m.openChapter("w", "c1", FakeChapters())
        val s = m.state.value
        assertTrue(s is ReaderState.Ready)
        assertEquals(3, s.pageCount)
        assertEquals(2, m.chapterCount())
    }

    @Test
    fun openMissingChapterErrors() = runTest {
        val m = ReaderStateMachine()
        m.openChapter("w", "nope", FakeChapters())
        val s = m.state.value
        assertTrue(s is ReaderState.Error)
        assertEquals(false, s.retryable)
    }

    @Test
    fun openFailureRetryable() = runTest {
        val m = ReaderStateMachine()
        m.openChapter("w", "c1", FakeChapters(failPayload = true))
        assertTrue(m.state.value is ReaderState.Error)
        assertTrue((m.state.value as ReaderState.Error).retryable)
    }

    @Test
    fun nextPrevChapter() = runTest {
        val m = ReaderStateMachine()
        m.openChapter("w", "c1", FakeChapters())
        m.nextChapter()
        assertEquals("c2", m.currentChapterId())
        m.nextChapter()
        assertTrue(m.state.value is ReaderState.ChapterEnd)
        assertTrue(m.prevChapter())
        assertEquals("c1", m.currentChapterId())
        assertEquals(false, m.prevChapter())
    }

    @Test
    fun preloadWindowShape() = runTest {
        val m = ReaderStateMachine()
        m.openChapter("w", "c1", FakeChapters())
        m.pageTo(1) // 共 3 页，当前 1：前1后2 → [0,1,2]
        assertEquals(listOf(0, 1, 2), m.preloadWindow())
        m.pageTo(0)
        assertEquals(listOf(0, 1, 2), m.preloadWindow())
        m.pageTo(2)
        assertEquals(listOf(1, 2), m.preloadWindow())
        assertEquals(emptyList(), ReaderStateMachine().preloadWindow())
    }

    @Test
    fun pageIndexClamped() = runTest {
        val m = ReaderStateMachine()
        m.openChapter("w", "c1", FakeChapters())
        m.pageTo(99)
        assertEquals(2, (m.state.value as ReaderState.Reading).pageIndex)
    }
}
