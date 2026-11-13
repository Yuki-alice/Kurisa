package feature.reader

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import core.model.ChapterPayload
import core.model.ReadingProgress
import core.reader.ChapterRef
import core.reader.ReadingConfig
import data.images.FetchedImage
import data.images.ImageFetcher
import data.images.ImageRequest
import data.repository.BookmarkRepository
import data.repository.ProgressRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 书签面板 UI 测试（desktop，JVM）：面板打开渲染书签摘要，点击跳转后面板关闭。
 * 跳转滚动本身由 Viewer 的 seek 门控执行（与锚点恢复同链路，截图测试覆盖渲染）。
 */
class ReaderBookmarkSheetTest {

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

    private class FakeBookmarks : BookmarkRepository {
        val store = mutableMapOf<String, core.model.Bookmark>()
        override suspend fun findByChapter(workId: String, chapterId: String) =
            store.values.firstOrNull { it.workId == workId && it.chapterId == chapterId }
        override suspend fun listForWork(workId: String) =
            store.values.filter { it.workId == workId }
        override suspend fun upsert(bookmark: core.model.Bookmark) {
            store[bookmark.id] = bookmark
        }
        override suspend fun removeByChapter(workId: String, chapterId: String) {
            store.entries.removeIf { it.value.workId == workId && it.value.chapterId == chapterId }
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
    fun bookmarkSheetShowsExcerptAndJumpClosesIt() {
        val repo = FakeBookmarks()
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
            bookmarks = repo,
        )
        // 预置一条书签（经仓库，与生产路径一致）。
        kotlinx.coroutines.runBlocking {
            repo.upsert(core.model.Bookmark("w:c1", "w", "c1", 3, "定位片段摘要", null, 1L))
        }
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.waitForIdle()
        // 打开面板前摘要不可见（面板未渲染）。
        rule.onAllNodesWithText("定位片段摘要", substring = true).assertCountEquals(0)
        m.onIntent(ReaderContract.Intent.OpenBookmarks)
        rule.waitUntil(15_000) { m.state.value.bookmarksVisible }
        rule.waitForIdle()
        // 面板渲染摘要；点击后跳转（同章 seek）并关闭面板。
        rule.onNodeWithText("定位片段摘要", substring = true).assertIsDisplayed()
        rule.onNodeWithText("定位片段摘要", substring = true).performClick()
        rule.waitUntil(15_000) { !m.state.value.bookmarksVisible }
        rule.waitForIdle()
        rule.onAllNodesWithText("定位片段摘要", substring = true).assertCountEquals(0)
    }

    @Test
    fun bookmarkNoteDialogSavesAndShowsNote() {
        val repo = FakeBookmarks()
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
            bookmarks = repo,
        )
        kotlinx.coroutines.runBlocking {
            repo.upsert(core.model.Bookmark("w:c1", "w", "c1", 3, "定位片段摘要", null, 1L))
        }
        rule.setContent { ReaderScreenVm(workId = "w", onBack = {}, vm = m) }
        rule.waitUntil(15_000) { m.state.value.novel != null }
        rule.waitUntil(15_000) { m.state.value.pageCount > 0 }
        rule.waitForIdle()
        // 打开面板，点编辑笔记弹出对话框。
        m.onIntent(ReaderContract.Intent.OpenBookmarks)
        rule.waitUntil(15_000) { m.state.value.bookmarksVisible }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("编辑笔记").performClick()
        rule.waitUntil(15_000) { m.state.value.editingNoteId == "w:c1" }
        rule.waitForIdle()
        rule.onNodeWithText("书签笔记").assertIsDisplayed()
        // 经意图保存（与对话框确认按钮同链路），面板内出现笔记，仓库落盘。
        m.onIntent(ReaderContract.Intent.SaveNote("我的想法"))
        rule.waitUntil(15_000) { m.state.value.editingNoteId == null }
        rule.waitForIdle()
        rule.onNodeWithText("笔记：我的想法", substring = true).assertIsDisplayed()
        kotlinx.coroutines.runBlocking {
            assertEquals("我的想法", repo.findByChapter("w", "c1")?.note)
        }
    }
}
