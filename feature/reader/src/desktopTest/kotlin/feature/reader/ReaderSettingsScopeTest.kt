package feature.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import core.model.ChapterPayload
import core.model.ReadingProgress
import core.reader.ChapterRef
import core.reader.ReadingConfig
import data.images.FetchedImage
import data.images.ImageFetcher
import data.images.ImageRequest
import data.repository.ProgressRepository
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 按书设置 UI 测试（desktop，JVM）：设置面板展示按书状态，恢复全局按钮回退字号。
 */
class ReaderSettingsScopeTest {

    @get:Rule
    val rule = createComposeRule()

    private class TextChapters : core.reader.ChapterProvider {
        override suspend fun chapters(workId: String) = listOf(ChapterRef("c1", "第一章", 1))
        override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
            ChapterPayload.Text(
                body = "<p>正文第一段内容足够长以便分页测量使用。</p>",
                format = core.model.TextFormat.HTML,
            )
    }

    private class StubImages : ImageFetcher {
        override suspend fun fetch(request: ImageRequest): FetchedImage =
            FetchedImage(ByteArray(0), request.url)
    }

    private class NoopProgress : ProgressRepository {
        override suspend fun save(progress: ReadingProgress) = Unit
        override suspend fun load(workId: String): ReadingProgress? = null
    }

    @Test
    fun resetBookSettingsRestoresGlobalTypography() {
        val store = InMemoryReaderSettingsStore()
        val m = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = core.reader.ReaderStateMachine(),
            chapters = TextChapters(),
            saveProgress = SaveReadingProgressUseCase(NoopProgress()),
            loadProgress = LoadReadingProgressUseCase(NoopProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            settingsStore = store,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.waitForIdle()
        // 本书改大字号后打开设置面板，应提示按书生效。
        m.onIntent(ReaderContract.Intent.NovelFontSizeChanged(24f))
        rule.waitForIdle()
        m.onIntent(ReaderContract.Intent.OpenSettings)
        rule.waitUntil(15_000) { m.state.value.settingsVisible }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("本书单独设置生效中").assertIsDisplayed()
        // 恢复全局：字号回退默认 18；作用域仍在本书，提示行保留。
        rule.onNodeWithText("恢复全局设置").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals(18f, m.state.value.settings.typography.fontSizeSp)
        rule.onNodeWithText("本书单独设置生效中", substring = true).assertIsDisplayed()
    }
}
