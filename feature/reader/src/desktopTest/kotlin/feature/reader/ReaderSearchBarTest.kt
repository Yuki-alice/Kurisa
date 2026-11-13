package feature.reader

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import core.model.ChapterPayload
import core.model.ReadingProgress
import core.reader.ChapterRef
import core.reader.ReadingConfig
import data.images.FetchedImage
import data.images.ImageFetcher
import data.images.ImageRequest
import data.repository.HighlightRepository
import data.repository.NoopBookmarkRepository
import data.repository.ProgressRepository
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 搜索条 UI 测试（desktop，JVM）：面板打开→计数渲染→下/上个切换→关闭。
 * 滚动执行由 Viewer seek 门控完成（与锚点恢复同链路）；此处只断言 UI 接线。
 */
class ReaderSearchBarTest {

    @get:Rule
    val rule = createComposeRule()

    private class TextChapters : core.reader.ChapterProvider {
        override suspend fun chapters(workId: String) = listOf(ChapterRef("c1", "第一章", 1))
        override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
            ChapterPayload.Text(
                body = "<p>正文第一段内容足够长以便分页测量使用。</p><p>第二段正文内容。</p>",
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

    private class FakeHighlights : HighlightRepository {
        val store = mutableMapOf<String, core.model.Highlight>()
        override suspend fun list(workId: String, chapterId: String) =
            store.values.filter { it.workId == workId && it.chapterId == chapterId }
        override suspend fun upsert(highlight: core.model.Highlight) {
            store[highlight.id] = highlight
        }
        override suspend fun remove(id: String) {
            store.remove(id)
        }
    }

    @Test
    fun searchBarShowsCountAndNavigatesAndCloses() {
        val m = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = core.reader.ReaderStateMachine(),
            chapters = TextChapters(),
            saveProgress = SaveReadingProgressUseCase(NoopProgress()),
            loadProgress = LoadReadingProgressUseCase(NoopProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            settingsStore = InMemoryReaderSettingsStore(
                ReadingConfig(theme = core.reader.ReaderTheme.WHITE),
            ),
            bookmarks = NoopBookmarkRepository,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.waitForIdle()
        // 顶栏入口打开搜索条（先开菜单，翻页自动收起不影响：首发被锚点门控抑制）。
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("搜索正文").performClick()
        rule.waitUntil(15_000) { m.state.value.searchVisible }
        rule.waitForIdle()
        rule.onNodeWithText("搜索正文", substring = true).assertIsDisplayed()
        // 输入查询（经意图驱动，与 TextField onValueChange 同链路）。
        m.onIntent(ReaderContract.Intent.SearchQueryChanged("正文"))
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("1/2").assertIsDisplayed()
        // 下一个切到第 2 个命中；关闭后搜索条消失。
        rule.onNodeWithContentDescription("下一个").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("2/2").assertIsDisplayed()
        rule.onNodeWithContentDescription("上一个").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("1/2").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭搜索").performClick()
        rule.waitUntil(15_000) { !m.state.value.searchVisible }
        rule.waitForIdle()
        rule.onAllNodesWithText("1/2").assertCountEquals(0)
    }

    @Test
    fun markAllSearchMatchesCreatesHighlights() {
        val highlights = FakeHighlights()
        val m = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = core.reader.ReaderStateMachine(),
            chapters = TextChapters(),
            saveProgress = SaveReadingProgressUseCase(NoopProgress()),
            loadProgress = LoadReadingProgressUseCase(NoopProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            settingsStore = InMemoryReaderSettingsStore(
                ReadingConfig(theme = core.reader.ReaderTheme.WHITE),
            ),
            highlights = highlights,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        // 经顶栏打开搜索条（与既有用例同链路）。
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("搜索正文").performClick()
        rule.waitUntil(15_000) { m.state.value.searchVisible }
        rule.waitForIdle()
        m.onIntent(ReaderContract.Intent.SearchQueryChanged("正文"))
        rule.waitForIdle()
        // 标出全部命中为划线（与搜索条「标出」按钮同链路）。
        rule.onNodeWithText("标出").performClick()
        rule.waitUntil(15_000) { m.state.value.highlightList.size == 2 }
        rule.waitForIdle()
        assertEquals(2, highlights.store.size)
    }
}
