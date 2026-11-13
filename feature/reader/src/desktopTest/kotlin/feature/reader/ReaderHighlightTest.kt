package feature.reader

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import core.model.ChapterPayload
import core.model.ReadingProgress
import core.reader.ChapterRef
import core.reader.ReadingConfig
import data.repository.HighlightRepository
import data.repository.NoopBookmarkRepository
import data.repository.NoopHighlightRepository
import data.images.FetchedImage
import data.images.ImageFetcher
import data.images.ImageRequest
import data.repository.ProgressRepository
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 划线 UI 测试（desktop，JVM）：划线模式下点段落即标，再点取消。
 * 区间存储与恢复走 VM/仓库单测；此处只验证手势→意图→状态接线。
 */
class ReaderHighlightTest {

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

    private class NoopProgress : ProgressRepository {
        override suspend fun save(progress: ReadingProgress) = Unit
        override suspend fun load(workId: String): ReadingProgress? = null
    }

    @Test
    fun highlightModeTapTogglesBlockHighlight() {
        val repo = FakeHighlights()
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
            highlights = repo,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // 开菜单进划线模式（点段落不翻页）。
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("划线模式").performClick()
        rule.waitUntil(15_000) { m.state.value.highlightMode }
        // 点第一段：落盘一条；再点取消（VM 写库在真线程，轮询而非只等合成空闲）。
        // 关闭菜单后再点段落：注入式点击走命中测试，顶栏覆盖层会吃掉首段点击。
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("正文第一段", substring = true).assertHasClickAction()
        rule.onNodeWithText("正文第一段", substring = true).performClick()
        // 状态同步切换（乐观更新），落盘异步追赶；模式位全程保持开启。
        rule.waitUntil(15_000) { m.state.value.highlightList.isNotEmpty() }
        rule.waitForIdle()
        assertTrue(m.state.value.highlightMode)
        assertEquals(1, m.state.value.highlightList.size)
        rule.waitUntil(15_000) { repo.store.isNotEmpty() }
        rule.onNodeWithText("正文第一段", substring = true).performClick()
        rule.waitUntil(15_000) { m.state.value.highlightList.isEmpty() }
        rule.waitForIdle()
        rule.waitUntil(15_000) { m.state.value.highlightMode }
        rule.waitUntil(15_000) { repo.store.isEmpty() }
        // 退出划线模式。
        m.onIntent(ReaderContract.Intent.ToggleHighlightMode)
        rule.waitUntil(15_000) { !m.state.value.highlightMode }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun longPressHighlightsWordUnderFinger() {
        val repo = FakeHighlights()
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
            highlights = repo,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // 开划线模式并关菜单，避免覆盖层吃掉手势。
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("划线模式").performClick()
        rule.waitUntil(15_000) { m.state.value.highlightMode }
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // 长按第一段：圈出命中词并落盘。
        rule.onNodeWithText("正文第一段", substring = true).performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.waitUntil(15_000) { m.state.value.highlightList.isNotEmpty() }
        val saved = m.state.value.highlightList.single()
        // 词级区间（单 CJK 字），而非整段（21 字）——断言真正走了圈词路径而非整段切换。
        assertTrue(saved.end - saved.start <= 2, "应为词级划线而非整段：$saved")
        rule.waitUntil(15_000) { repo.store.isNotEmpty() }
        assertEquals(saved, repo.store.values.single())
    }

    @Test
    fun longPressDoesNotToggleWholeBlock() {
        // 长按（无位移）只圈词，不应触发点按的整段切换：仓库恰好一条词级记录。
        val repo = FakeHighlights()
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
            highlights = repo,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        m.onIntent(ReaderContract.Intent.ToggleHighlightMode)
        rule.waitForIdle()
        rule.waitUntil(15_000) { m.state.value.highlightMode }
        rule.onNodeWithText("正文第一段", substring = true).performTouchInput { longClick() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.waitUntil(15_000) { m.state.value.highlightList.isNotEmpty() }
        rule.waitForIdle()
        // 若点按被误触发，会多出一条整段记录（w:c1:0-21）。
        assertEquals(1, repo.store.size)
        assertTrue(repo.store.values.single().end - repo.store.values.single().start <= 2)
    }

    @Test
    fun highlightNoteDialogSavesAndShows() {
        val repo = FakeHighlights()
        // 先落盘再建 VM：onReady 在初始化时一次性加载划线。
        kotlinx.coroutines.runBlocking {
            repo.upsert(core.model.Highlight("w:c1:0-8", "w", "c1", 0, 8, "划线摘录文本", 1L))
        }
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
            highlights = repo,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.waitForIdle()
        // 打开面板点编辑划线笔记，对话框出现后经意图保存（与确认按钮同链路）。
        m.onIntent(ReaderContract.Intent.OpenBookmarks)
        rule.waitUntil(15_000) { m.state.value.bookmarksVisible }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("编辑划线笔记").performClick()
        rule.waitUntil(15_000) { m.state.value.editingHighlightNoteId == "w:c1:0-8" }
        rule.waitForIdle()
        rule.onNodeWithText("划线笔记").assertIsDisplayed()
        m.onIntent(ReaderContract.Intent.SaveHighlightNote("划线想法"))
        rule.waitUntil(15_000) { m.state.value.editingHighlightNoteId == null }
        rule.waitForIdle()
        rule.onNodeWithText("笔记：划线想法", substring = true).assertIsDisplayed()
        assertEquals("划线想法", repo.store["w:c1:0-8"]?.note)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun dragSelectsRangeAndMarksIt() {
        val repo = FakeHighlights()
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
            highlights = repo,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // 开划线模式（点段落不翻页）后关菜单，避免覆盖层吃掉手势。
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("划线模式").performClick()
        rule.waitUntil(15_000) { m.state.value.highlightMode }
        m.onIntent(ReaderContract.Intent.ToggleMenu)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        // 长按拖拽第一段前部（文本只占节点左部，居中点是空白会被钳制）：选区出现。
        val textNode = rule.onNodeWithText("正文第一段", substring = true).fetchSemanticsNode()
        val w = textNode.size.width.toFloat()
        val h = textNode.size.height.toFloat()
        val startPt = androidx.compose.ui.geometry.Offset(w * 0.10f, h / 2f)
        val endPt = androidx.compose.ui.geometry.Offset(w * 0.30f, h / 2f)
        rule.onNodeWithText("正文第一段", substring = true).performTouchInput {
            down(0, startPt)
            advanceEventTime(800)
            moveTo(endPt)
            advanceEventTime(200)
            up(0)
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.waitUntil(15_000) { m.state.value.selectionRange != null }
        val range = m.state.value.selectionRange!!
        assertTrue(range.last > range.first)
        // 点「标出所选」：落盘并清选区。
        rule.onNodeWithText("标出所选").performClick()
        rule.waitUntil(15_000) { repo.store.isNotEmpty() }
        rule.waitForIdle()
        assertEquals(null, m.state.value.selectionRange)
        assertEquals(1, m.state.value.highlightList.size)
        assertEquals(range.first, m.state.value.highlightList.single().start)
    }

    @Test
    fun highlightSheetJumpAndDelete() {
        val repo = FakeHighlights()
        // 先落盘再建 VM：onReady 在 VM 初始化时异步打开章节并一次性加载划线。
        kotlinx.coroutines.runBlocking {
            repo.upsert(core.model.Highlight("w:c1:0-8", "w", "c1", 0, 8, "划线摘录文本", 1L))
        }
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
            highlights = repo,
        )
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.waitForIdle()
        // 书签面板内本章划线分区：点行跳转并关闭面板。
        m.onIntent(ReaderContract.Intent.OpenBookmarks)
        rule.waitUntil(15_000) { m.state.value.bookmarksVisible }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithText("本章划线", substring = true).assertIsDisplayed()
        rule.waitUntil(15_000) { m.state.value.highlightList.isNotEmpty() }
        rule.waitForIdle()
        rule.onNodeWithText("划线摘录文本", substring = true).performClick()
        rule.waitUntil(15_000) { !m.state.value.bookmarksVisible }
        rule.waitUntil(15_000) { m.state.value.bookmarkSeek != null }
        assertEquals(0, m.state.value.bookmarkSeek?.charOffset)
        m.onIntent(
            ReaderContract.Intent.BookmarkSeekHandled(m.state.value.bookmarkSeek!!.nonce),
        )
        // 重开面板删除该条。
        m.onIntent(ReaderContract.Intent.OpenBookmarks)
        rule.waitUntil(15_000) { m.state.value.bookmarksVisible }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("删除划线").performClick()
        rule.waitUntil(15_000) { repo.store.isEmpty() }
        rule.waitForIdle()
        rule.onAllNodesWithText("划线摘录文本", substring = true).assertCountEquals(0)
    }
}
