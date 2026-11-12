package core.reader

import core.model.ChapterPayload
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderStateMachineInteractionTest {

    private class FlakyProvider : ChapterProvider {
        var catalogCalls = 0
        var failCatalog = true
        override suspend fun chapters(workId: String): List<ChapterRef> {
            catalogCalls++
            if (failCatalog) throw IllegalStateException("catalog down")
            return listOf(ChapterRef("c1", "第1话", 1), ChapterRef("c2", "第2话", 2))
        }

        override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
            ChapterPayload.Images(listOf("https://example.com/$chapterId-1.jpg"))
    }

    @Test
    fun retryRecoversFromCatalogFailure() = runTest {
        val provider = FlakyProvider()
        val machine = ReaderStateMachine()
        machine.openChapter("w", "c1", provider)
        val error = machine.state.value
        assertTrue(error is ReaderState.Error && error.retryable)

        provider.failCatalog = false
        machine.retry()
        assertTrue(machine.state.value is ReaderState.Ready)
        assertEquals(2, machine.chapterCount())
        assertEquals("c1", machine.currentChapterId())
    }

    @Test
    fun chapterNavigationAccessors() = runTest {
        val machine = ReaderStateMachine()
        machine.openChapter("w", "c1", FlakyProvider().apply { failCatalog = false })
        assertFalse(machine.hasPreviousChapter())
        assertTrue(machine.hasNextChapter())
        assertEquals("c2", machine.nextChapterId())
        assertEquals(null, machine.previousChapterId())

        machine.jumpToChapter("c2")
        assertTrue(machine.hasPreviousChapter())
        assertFalse(machine.hasNextChapter())
        assertEquals("c2", machine.currentChapterId())
    }

    @Test
    fun resolvePageDefaultsToPlainUrl() = runTest {
        val provider = FlakyProvider().apply { failCatalog = false }
        val request = provider.resolvePage("w", "c1", "https://example.com/a.jpg")
        assertEquals("https://example.com/a.jpg", request.effectiveUrl)
        assertTrue(request.headers.isEmpty())
    }
}
