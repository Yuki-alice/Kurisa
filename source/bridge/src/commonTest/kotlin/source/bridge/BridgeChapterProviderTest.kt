package source.bridge

import core.model.ChapterPayload
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import source.contract.ImageLoadConfig
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
    fun novelFormatMapping() = runTest {
        val html = BridgeChapterProvider(
            FakeCalls(chapter = """{"content":"<p>hi</p>","format":"html"}"""),
            ContentKind.NOVEL,
        )
        assertEquals(
            core.model.TextFormat.HTML,
            (html.payload("w", "c") as ChapterPayload.Text).format,
        )
        val md = BridgeChapterProvider(
            FakeCalls(chapter = """{"content":"# t","format":"markdown"}"""),
            ContentKind.NOVEL,
        )
        assertEquals(
            core.model.TextFormat.MARKDOWN,
            (md.payload("w", "c") as ChapterPayload.Text).format,
        )
        val plain = BridgeChapterProvider(
            FakeCalls(chapter = """{"text":"body","format":"plain"}"""),
            ContentKind.NOVEL,
        )
        assertEquals(
            core.model.TextFormat.PLAIN,
            (plain.payload("w", "c") as ChapterPayload.Text).format,
        )
        // 未知格式收敛为 HTML（渲染侧白名单兜底，不静默丢正文）。
        val unknown = BridgeChapterProvider(
            FakeCalls(chapter = """{"content":"body","format":"docx"}"""),
            ContentKind.NOVEL,
        )
        assertEquals(
            core.model.TextFormat.HTML,
            (unknown.payload("w", "c") as ChapterPayload.Text).format,
        )
        // content 优先于 text（真实形状优先，M1 合成例兜底）。
        val both = BridgeChapterProvider(
            FakeCalls(chapter = """{"content":"new","text":"old","format":"plain"}"""),
            ContentKind.NOVEL,
        )
        assertEquals("new", (both.payload("w", "c") as ChapterPayload.Text).body)
    }

    @Test
    fun videoAudioDeferredExplicitly() = runTest {
        val video = BridgeChapterProvider(
            FakeCalls(chapter = """{"images":["u1"]}"""),
            ContentKind.VIDEO,
        )
        val err = assertFailsWith<SourceBridgeException> { video.payload("w", "c") }
        assertTrue(err.message!!.contains("未接线"), err.message)
        val audio = BridgeChapterProvider(
            FakeCalls(chapter = """{"content":"body","format":"plain"}"""),
            ContentKind.AUDIO,
        )
        assertFailsWith<SourceBridgeException> { audio.payload("w", "c") }
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
    fun resolvePageAppliesOnImageLoadConfig() = runTest {
        val calls = object : SourceCalls {
            override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int) = ""
            override suspend fun defaultSearchOptions() = "[]"
            override suspend fun loadWorkDetails(workId: String, kind: ContentKind) = ""
            override suspend fun loadChapterPayload(workId: String, chapterId: String, kind: ContentKind) = ""
            override suspend fun invoke(path: String, argsJson: String) = ""
            override suspend fun imageLoadConfig(
                url: String,
                workId: String,
                chapterId: String,
            ) = ImageLoadConfig(
                url = "https://cdn.example.com/rewritten.jpg",
                headers = mapOf("Referer" to "https://example.com/"),
                maxRetries = 2,
            )
        }
        val p = BridgeChapterProvider(calls, ContentKind.MANGA)
        val request = p.resolvePage("w", "c", "https://example.com/original.jpg")
        assertEquals("https://example.com/original.jpg", request.url)
        assertEquals("https://cdn.example.com/rewritten.jpg", request.effectiveUrl)
        assertEquals("https://example.com/", request.headers["Referer"])
        assertEquals(2, request.maxRetries)
    }

    @Test
    fun dispatchersConfined() = runTest {
        val d = DefaultSourceDispatchers()
        assertTrue(d.dispatcherFor("a") === d.dispatcherFor("a"))
    }
}
