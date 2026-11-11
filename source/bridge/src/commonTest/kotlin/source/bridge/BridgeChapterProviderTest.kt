package source.bridge

import core.model.ChapterPayload
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BridgeChapterProviderTest {

    class FakeCalls(
        var details: String = "",
        var chapter: String = "",
    ) : SourceCalls {
        override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int) = ""
        override suspend fun defaultSearchOptions() = "[]"
        override suspend fun loadWorkDetails(workId: String, kind: ContentKind) = details
        override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind) = chapter
        override suspend fun invoke(path: String, argsJson: String) = ""
    }

    @Test
    fun mapStringChapters() = runTest {
        val p = BridgeChapterProvider(
            FakeCalls(details = """{"title":"W","chapters":{"c2":"第二话","c1":"第一话"}}"""),
            ContentKind.MANGA,
        )
        val chapters = p.chapters("w")
        assertEquals(listOf("c2", "c1"), chapters.map { it.id })
        assertEquals("第二话", chapters[0].title)
        assertEquals(1, chapters[0].ordinal)
    }

    @Test
    fun mapObjectChapters() = runTest {
        val p = BridgeChapterProvider(
            FakeCalls(details = """{"title":"W","chapters":{"c1":{"id":"c1","title":"第一话"}}}"""),
            ContentKind.MANGA,
        )
        assertEquals("第一话", p.chapters("w").single().title)
    }

    @Test
    fun arrayChapters() = runTest {
        val p = BridgeChapterProvider(
            FakeCalls(details = """{"title":"N","chapters":[{"id":"ch1","title":"第一章"}]}"""),
            ContentKind.NOVEL,
        )
        val chapters = p.chapters("w")
        assertEquals("ch1", chapters.single().id)
    }

    @Test
    fun mangaImagesAndNovelBodies() = runTest {
        val manga = BridgeChapterProvider(
            FakeCalls(chapter = """{"images":["u1","u2"]}"""),
            ContentKind.MANGA,
        )
        val mi = manga.payload("w", "c") as ChapterPayload.Images
        assertEquals(listOf("u1", "u2"), mi.urls)

        val novelContent = BridgeChapterProvider(
            FakeCalls(chapter = """{"content":"正文"}"""),
            ContentKind.NOVEL,
        )
        assertEquals("正文", (novelContent.payload("w", "c") as ChapterPayload.Text).body)

        val novelText = BridgeChapterProvider(
            FakeCalls(chapter = """{"text":"旧体"}"""),
            ContentKind.NOVEL,
        )
        assertEquals("旧体", (novelText.payload("w", "c") as ChapterPayload.Text).body)
    }

    @Test
    fun missingFieldsThrow() = runTest {
        val noChapters = BridgeChapterProvider(
            FakeCalls(details = """{"title":"W"}"""), ContentKind.MANGA
        )
        assertFailsWith<SourceBridgeException> { noChapters.chapters("w") }

        val noImages = BridgeChapterProvider(
            FakeCalls(chapter = """{"nope":1}"""), ContentKind.MANGA
        )
        assertFailsWith<SourceBridgeException> { noImages.payload("w", "c") }

        val badJson = BridgeChapterProvider(
            FakeCalls(details = "not json"), ContentKind.MANGA
        )
        assertFailsWith<SourceBridgeException> { badJson.chapters("w") }
    }

    @Test
    fun dispatchersConfined() = runTest {
        val d = DefaultSourceDispatchers()
        assertTrue(d.dispatcherFor("a") === d.dispatcherFor("a"))
    }
}
