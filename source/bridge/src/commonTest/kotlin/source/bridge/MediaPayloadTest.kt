package source.bridge

import core.model.ChapterPayload
import core.reader.pageCountOf
import kotlinx.coroutines.test.runTest
import source.contract.ContentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BE-C4：VIDEO / AUDIO 类型化 payload，不伪装成漫画 images。 */
class MediaPayloadTest {

    private class Calls(private val payloadJson: String) : SourceCalls {
        override suspend fun searchWorks(keyword: String, optionsJson: String, page: Int) = ""
        override suspend fun defaultSearchOptions() = "[]"
        override suspend fun loadWorkDetails(workId: String, kind: ContentKind) = "{}"
        override suspend fun loadChapterPayload(
            workId: String,
            chapterId: String,
            kind: ContentKind,
        ) = payloadJson
        override suspend fun invoke(path: String, argsJson: String) = ""
    }

    @Test
    fun videoPayloadIsTypedAndCountsAsOnePage() = runTest {
        val provider = BridgeChapterProvider(
            Calls(
                """{"url":"https://cdn.example.com/v.m3u8","mimeType":"application/x-mpegURL",""" +
                    """"headers":{"Referer":"https://example.com"},""" +
                    """"subtitles":["https://cdn.example.com/zh.vtt"]}""",
            ),
            ContentKind.VIDEO,
        )
        val payload = provider.payload("w", "c1")
        assertTrue(payload is ChapterPayload.Video)
        assertEquals("https://cdn.example.com/v.m3u8", payload.url)
        assertEquals("application/x-mpegURL", payload.mimeType)
        assertEquals("https://example.com", payload.headers["Referer"])
        assertEquals(listOf("https://cdn.example.com/zh.vtt"), payload.subtitles)
        assertEquals(1, pageCountOf(payload))
    }

    @Test
    fun audioPayloadIsTypedAndCountsAsOnePage() = runTest {
        val provider = BridgeChapterProvider(
            Calls(
                """{"url":"https://cdn.example.com/a.mp3","mimeType":"audio/mpeg",""" +
                    """"title":"第 1 集","durationMs":123456}""",
            ),
            ContentKind.AUDIO,
        )
        val payload = provider.payload("w", "c1")
        assertTrue(payload is ChapterPayload.Audio)
        assertEquals("https://cdn.example.com/a.mp3", payload.url)
        assertEquals("第 1 集", payload.title)
        assertEquals(123456L, payload.durationMs)
        assertEquals(1, pageCountOf(payload))
    }

    @Test
    fun missingUrlFailsExplicitlyInsteadOfFallingBackToImages() = runTest {
        val video = BridgeChapterProvider(Calls("{}"), ContentKind.VIDEO)
        assertFailsWith<SourceBridgeException> { video.payload("w", "c") }
        val audio = BridgeChapterProvider(Calls("""{"images":["u"]}"""), ContentKind.AUDIO)
        assertFailsWith<SourceBridgeException> { audio.payload("w", "c") }
    }

    @Test
    fun malformedHeadersAreIgnoredNotFatal() = runTest {
        val provider = BridgeChapterProvider(
            Calls("""{"url":"u","headers":{"bad":{"nested":1},"ok":"v"}}"""),
            ContentKind.VIDEO,
        )
        val payload = provider.payload("w", "c") as ChapterPayload.Video
        assertEquals(mapOf("ok" to "v"), payload.headers)
    }
}
