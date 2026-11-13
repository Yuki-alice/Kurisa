package feature.reader

import core.model.ChapterPayload
import core.model.ReadingProgress
import core.model.TextFormat
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ReaderStateMachine
import data.images.FetchedImage
import data.images.ImageFetcher
import data.images.ImageRequest
import data.repository.BookmarkRepository
import data.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 小说载荷 → 结构化正文 → 虚拟页数 → 字符锚点进度（commonTest）。
 * 与 ReaderViewModelTest 同样的调度器纪律：共享 dispatcher + backgroundScope 收集。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelNovelTest {

    private class TextProvider : ChapterProvider {
        override suspend fun chapters(workId: String) = listOf(
            ChapterRef("c1", "第一章", 1),
            ChapterRef("c2", "第二章", 2),
        )

        override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
            ChapterPayload.Text(
                body = "<p>正文第一段 &amp; 第二段</p><script>evil()</script><h2>小节</h2><p>下一段内容</p>",
                format = TextFormat.HTML,
            )
    }

    private class FakeProgress : ProgressRepository {
        val saved = mutableListOf<ReadingProgress>()
        var stored: ReadingProgress? = null
        override suspend fun save(progress: ReadingProgress) {
            saved += progress
            stored = progress
        }

        override suspend fun load(workId: String): ReadingProgress? = stored
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

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(progress: FakeProgress) = ReaderViewModel(
        workId = "w",
        chapterId = "c1",
        machine = ReaderStateMachine(),
        chapters = TextProvider(),
        saveProgress = SaveReadingProgressUseCase(progress),
        loadProgress = LoadReadingProgressUseCase(progress),
        pages = LoadPageBytesUseCase(StubImages()),
        domains = emptySet(),
    )

    @Test
    fun textPayloadProducesStructuredDocumentAndVirtualPages() = runTest(dispatcher) {
        val progress = FakeProgress()
        val model = vm(progress)
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()

        val state = states.last()
        val doc = state.novel
        assertTrue(doc != null)
        assertTrue(doc.blocks.any { it.text.contains("正文第一段 & 第二段") })
        assertTrue(doc.blocks.none { it.text.contains("evil()") })
        assertTrue(doc.blocks.any { it.kind == core.model.NovelBlockKind.HEADING })

        model.onIntent(ReaderContract.Intent.NovelPagesReady(7, "layout-key-1"))
        runCurrent()
        assertEquals(7, states.last().pageCount)
        assertEquals("layout-key-1", states.last().novelLayoutKey)
    }

    @Test
    fun novelAnchorIsDebouncedSavedAndRestored() = runTest(dispatcher) {
        val progress = FakeProgress()
        val model = vm(progress)
        backgroundScope.launch { model.state.collect {} }
        runCurrent()

        model.onIntent(ReaderContract.Intent.NovelPagesReady(4, "k1"))
        model.onIntent(ReaderContract.Intent.NovelAnchorChanged(123, "k1"))
        runCurrent()
        advanceTimeBy(501)
        assertEquals(123, progress.saved.last().charOffset)
        assertEquals("k1", progress.saved.last().layoutKey)

        val reopened = vm(progress)
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { reopened.state.collect { states += it } }
        runCurrent()
        assertEquals(123, states.last().novelAnchor)
        assertEquals("k1", states.last().novelLayoutKey)
    }

    @Test
    fun novelImageBlocksAreRewrittenViaProvider() = runTest(dispatcher) {
        val progress = FakeProgress()
        val rewriting = object : ChapterProvider {
            override suspend fun chapters(workId: String) = listOf(
                ChapterRef("c1", "\u7b2c\u4e00\u7ae0", 1),
            )
            override suspend fun payload(workId: String, chapterId: String): ChapterPayload =
                ChapterPayload.Text(
                    body = "<p>\u6b63\u6587</p><img src=\"https://example.com/o.jpg\"/>" ,
                    format = TextFormat.HTML,
                )
            override suspend fun resolveNovelImage(url: String): core.reader.ReaderPageRequest =
                core.reader.ReaderPageRequest(
                    url,
                    rewriteUrl = "https://cdn.example.com/r.jpg",
                    headers = mapOf("Referer" to "https://example.com/"),
                )
        }
        val model = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = rewriting,
            saveProgress = SaveReadingProgressUseCase(progress),
            loadProgress = LoadReadingProgressUseCase(progress),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        val images = states.last().novel?.blocks
            ?.filter { it.kind == core.model.NovelBlockKind.IMAGE }
            .orEmpty()
        assertTrue(images.isNotEmpty())
        assertEquals("https://cdn.example.com/r.jpg", images.first().imageUrl)
        assertEquals(
            "https://example.com/",
            states.last().novelImageHeaders["https://cdn.example.com/r.jpg"]?.get("Referer"),
        )
    }

    @Test
    fun highlightJumpSeeksAndRemoveDeletes() = runTest(dispatcher) {
        val repo = FakeHighlights()
        repo.upsert(core.model.Highlight("w:c1:10-20", "w", "c1", 10, 20, "摘", 1L))
        fun open() = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            highlights = repo,
        )
        val model = open()
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        assertEquals(1, states.last().highlightList.size)
        // 跳转：同章 seek 并关闭面板。
        model.onIntent(ReaderContract.Intent.OpenBookmarks)
        runCurrent()
        model.onIntent(ReaderContract.Intent.JumpToHighlight("w:c1:10-20"))
        runCurrent()
        assertEquals(10, states.last().bookmarkSeek?.charOffset)
        assertEquals(false, states.last().bookmarksVisible)
        model.onIntent(ReaderContract.Intent.BookmarkSeekHandled(states.last().bookmarkSeek!!.nonce))
        runCurrent()
        assertEquals(null, states.last().bookmarkSeek)
        // 删除：列表与落盘同步移除。
        model.onIntent(ReaderContract.Intent.RemoveHighlight("w:c1:10-20"))
        runCurrent()
        assertTrue(states.last().highlightList.isEmpty())
        assertTrue(repo.store.isEmpty())
    }

    @Test
    fun toggleBookmarkPersistsAndRestores() = runTest(dispatcher) {
        val repo = FakeBookmarks()
        fun open() = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            bookmarks = repo,
        )
        val model = open()
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        assertEquals(false, states.last().bookmarked)
        // 小说锚点 0 处打标：position 为字符锚点，excerpt 为正文窗口。
        model.onIntent(ReaderContract.Intent.ToggleBookmark)
        runCurrent()
        assertEquals(true, states.last().bookmarked)
        val saved = repo.findByChapter("w", "c1")
        assertTrue(saved != null)
        assertEquals(0, saved.position)
        assertTrue(!saved.excerpt.isNullOrEmpty())
        // 重开同章恢复点亮；再切一次取消并落盘删除。
        val reopened = open()
        val states2 = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { reopened.state.collect { states2 += it } }
        runCurrent()
        assertEquals(true, states2.last().bookmarked)
        reopened.onIntent(ReaderContract.Intent.ToggleBookmark)
        runCurrent()
        assertEquals(false, states2.last().bookmarked)
        assertEquals(null, repo.findByChapter("w", "c1"))
    }

    @Test
    fun openBookmarksLoadsList() = runTest(dispatcher) {
        val repo = FakeBookmarks()
        repo.upsert(core.model.Bookmark("w:c1", "w", "c1", 10, "摘", null, 1L))
        val model = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            bookmarks = repo,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        assertEquals(false, states.last().bookmarksVisible)
        model.onIntent(ReaderContract.Intent.OpenBookmarks)
        runCurrent()
        assertEquals(true, states.last().bookmarksVisible)
        assertEquals(listOf("w:c1"), states.last().bookmarkList.map { it.id })
        model.onIntent(ReaderContract.Intent.CloseBookmarks)
        runCurrent()
        assertEquals(false, states.last().bookmarksVisible)
    }

    @Test
    fun jumpToBookmarkSameChapterSeeksAndHandles() = runTest(dispatcher) {
        val repo = FakeBookmarks()
        repo.upsert(core.model.Bookmark("w:c1", "w", "c1", 12, "摘", null, 1L))
        val model = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            bookmarks = repo,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        model.onIntent(ReaderContract.Intent.OpenBookmarks)
        runCurrent()
        model.onIntent(ReaderContract.Intent.JumpToBookmark("w:c1"))
        runCurrent()
        // 面板关闭，同章小说产生定位请求。
        assertEquals(false, states.last().bookmarksVisible)
        val seek = states.last().bookmarkSeek
        assertTrue(seek != null)
        assertEquals(12, seek.charOffset)
        // Viewer 回收后请求清除；错 nonce 不清。
        model.onIntent(ReaderContract.Intent.BookmarkSeekHandled(seek.nonce + 1))
        runCurrent()
        assertTrue(states.last().bookmarkSeek != null)
        model.onIntent(ReaderContract.Intent.BookmarkSeekHandled(seek.nonce))
        runCurrent()
        assertEquals(null, states.last().bookmarkSeek)
    }

    @Test
    fun jumpToBookmarkOtherChapterOpensWithAnchor() = runTest(dispatcher) {
        val repo = FakeBookmarks()
        repo.upsert(core.model.Bookmark("w:c2", "w", "c2", 7, "摘", null, 1L))
        val model = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            bookmarks = repo,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        model.onIntent(ReaderContract.Intent.OpenBookmarks)
        runCurrent()
        model.onIntent(ReaderContract.Intent.JumpToBookmark("w:c2"))
        runCurrent()
        assertEquals("c2", states.last().chapterId)
        assertEquals(7, states.last().novelAnchor)
    }

    @Test
    fun jumpToBookmarkMangaPagesToPosition() = runTest(dispatcher) {
        val repo = FakeBookmarks()
        repo.upsert(core.model.Bookmark("w:c1", "w", "c1", 2, null, null, 1L))
        val images = object : ChapterProvider {
            override suspend fun chapters(workId: String) = listOf(ChapterRef("c1", "第一话", 1))
            override suspend fun payload(workId: String, chapterId: String) =
                ChapterPayload.Images(listOf("p0", "p1", "p2"))
        }
        val model = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = images,
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            bookmarks = repo,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        model.onIntent(ReaderContract.Intent.OpenBookmarks)
        runCurrent()
        model.onIntent(ReaderContract.Intent.JumpToBookmark("w:c1"))
        runCurrent()
        assertEquals(2, states.last().pageIndex)
    }

    @Test
    fun searchFindsMatchesSeeksAndCycles() = runTest(dispatcher) {
        val model = vm(FakeProgress())
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        // "段" 在正文多处出现。
        model.onIntent(ReaderContract.Intent.SearchQueryChanged("段"))
        runCurrent()
        val matches = states.last().searchMatches
        assertTrue(matches.size >= 2)
        assertEquals(0, states.last().searchIndex)
        val firstSeek = states.last().bookmarkSeek
        assertTrue(firstSeek != null)
        assertEquals(matches[0].first, firstSeek.charOffset)
        // 下一个命中循环推进并重新 seek。
        model.onIntent(ReaderContract.Intent.SearchNext)
        runCurrent()
        assertEquals(1, states.last().searchIndex)
        assertEquals(matches[1].first, states.last().bookmarkSeek?.charOffset)
        // 回绕：(1+1)%size==0。
        repeat(matches.size - 1) {
            model.onIntent(ReaderContract.Intent.SearchNext)
            runCurrent()
        }
        assertEquals(0, states.last().searchIndex)
        // 上一个回绕到末尾。
        model.onIntent(ReaderContract.Intent.SearchPrev)
        runCurrent()
        assertEquals(matches.size - 1, states.last().searchIndex)
        // 无命中清空；关闭搜索复位。
        model.onIntent(ReaderContract.Intent.SearchQueryChanged("不存在xyz"))
        runCurrent()
        assertTrue(states.last().searchMatches.isEmpty())
        assertEquals(null, states.last().bookmarkSeek)
        model.onIntent(ReaderContract.Intent.CloseSearch)
        runCurrent()
        assertEquals("", states.last().searchQuery)
        assertTrue(states.last().searchMatches.isEmpty())
    }

    @Test
    fun bookmarkNoteEditSavesCapsAndCancels() = runTest(dispatcher) {
        val repo = FakeBookmarks()
        repo.upsert(core.model.Bookmark("w:c1", "w", "c1", 3, null, null, 1L))
        fun open() = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            bookmarks = repo,
        )
        val model = open()
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        // 打开编辑器并保存超长笔记：落盘截断 2000，列表同步，编辑器关闭。
        model.onIntent(ReaderContract.Intent.StartEditNote("w:c1"))
        runCurrent()
        assertEquals("w:c1", states.last().editingNoteId)
        model.onIntent(ReaderContract.Intent.SaveNote("n".repeat(2500)))
        runCurrent()
        assertEquals(null, states.last().editingNoteId)
        assertEquals(2000, repo.findByChapter("w", "c1")?.note?.length)
        model.onIntent(ReaderContract.Intent.OpenBookmarks)
        runCurrent()
        assertEquals(2000, states.last().bookmarkList.single().note?.length)
        // 取消编辑不改动已存笔记。
        model.onIntent(ReaderContract.Intent.StartEditNote("w:c1"))
        runCurrent()
        model.onIntent(ReaderContract.Intent.CancelEditNote)
        runCurrent()
        assertEquals(null, states.last().editingNoteId)
        assertEquals(2000, repo.findByChapter("w", "c1")?.note?.length)
        // 空串保存视为清除。
        model.onIntent(ReaderContract.Intent.StartEditNote("w:c1"))
        runCurrent()
        model.onIntent(ReaderContract.Intent.SaveNote("   "))
        runCurrent()
        assertEquals(null, repo.findByChapter("w", "c1")?.note)
    }

    @Test
    fun bookScopedTypographyIsIsolatedAndResettable() = runTest(dispatcher) {
        val store = InMemoryReaderSettingsStore()
        fun open(work: String): ReaderViewModel {
            val model = ReaderViewModel(
                workId = work,
                chapterId = "c1",
                machine = ReaderStateMachine(),
                chapters = TextProvider(),
                saveProgress = SaveReadingProgressUseCase(FakeProgress()),
                loadProgress = LoadReadingProgressUseCase(FakeProgress()),
                pages = LoadPageBytesUseCase(StubImages()),
                domains = emptySet(),
                settingsStore = store,
            )
            backgroundScope.launch { model.state.collect {} }
            return model
        }
        val m1 = open("w1")
        val states1 = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { m1.state.collect { states1 += it } }
        runCurrent()
        assertEquals("book:w1", states1.last().settingsScope)
        // 本书改 24：同 store 的另一本书不受影响。
        m1.onIntent(ReaderContract.Intent.NovelFontSizeChanged(24f))
        runCurrent()
        assertEquals(24f, states1.last().settings.typography.fontSizeSp)
        val m2 = open("w2")
        val states2 = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { m2.state.collect { states2 += it } }
        runCurrent()
        assertEquals("book:w2", states2.last().settingsScope)
        assertEquals(18f, states2.last().settings.typography.fontSizeSp)
        // 重置本书：回退全局 18。
        m1.onIntent(ReaderContract.Intent.ResetBookSettings)
        runCurrent()
        assertEquals(18f, m1.state.value.settings.typography.fontSizeSp)
    }

    private class FakeHighlights : data.repository.HighlightRepository {
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
    fun highlightModeTogglesBlocksAndRestoresOnOpen() = runTest(dispatcher) {
        val repo = FakeHighlights()
        fun open() = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            highlights = repo,
        )
        val model = open()
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        assertEquals(false, states.last().highlightMode)
        model.onIntent(ReaderContract.Intent.ToggleHighlightMode)
        runCurrent()
        assertEquals(true, states.last().highlightMode)
        // 标注块：落盘 + 进列表。
        model.onIntent(ReaderContract.Intent.ToggleBlockHighlight(0, 5))
        runCurrent()
        assertEquals(listOf("w:c1:0-5"), states.last().highlightList.map { it.id })
        assertEquals(5, repo.store["w:c1:0-5"]?.end)
        // 再次切换同一块：取消。
        model.onIntent(ReaderContract.Intent.ToggleBlockHighlight(0, 5))
        runCurrent()
        assertTrue(states.last().highlightList.isEmpty())
        assertTrue(repo.store.isEmpty())
        // 重开恢复落盘划线。
        model.onIntent(ReaderContract.Intent.ToggleBlockHighlight(10, 20))
        runCurrent()
        val reopened = open()
        val states2 = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { reopened.state.collect { states2 += it } }
        runCurrent()
        assertEquals(listOf("w:c1:10-20"), states2.last().highlightList.map { it.id })
    }

    @Test
    fun highlightAllSearchMatchesCreatesHighlights() = runTest(dispatcher) {
        val repo = FakeHighlights()
        val model = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            highlights = repo,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        // TextProvider 正文含两处“段”：全部标出得两条，摘要为命中原文。
        model.onIntent(ReaderContract.Intent.SearchQueryChanged("段"))
        runCurrent()
        val matches = states.last().searchMatches
        assertTrue(matches.size >= 2)
        model.onIntent(ReaderContract.Intent.HighlightAllSearchMatches)
        runCurrent()
        assertEquals(matches.size, states.last().highlightList.size)
        val first = states.last().highlightList.first()
        assertEquals("w:c1:${matches[0].first}-${matches[0].last + 1}", first.id)
        // 再次执行不重复（同 id upsert 替换）。
        model.onIntent(ReaderContract.Intent.HighlightAllSearchMatches)
        runCurrent()
        assertEquals(matches.size, states.last().highlightList.size)
        assertEquals(matches.size, repo.store.size)
    }

    @Test
    fun highlightNoteEditSavesAndClosesDialog() = runTest(dispatcher) {
        val repo = FakeHighlights()
        repo.upsert(core.model.Highlight("w:c1:10-20", "w", "c1", 10, 20, "摘", 1L))
        val model = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            highlights = repo,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        assertEquals(1, states.last().highlightList.size)
        model.onIntent(ReaderContract.Intent.StartEditHighlightNote("w:c1:10-20"))
        runCurrent()
        assertEquals("w:c1:10-20", states.last().editingHighlightNoteId)
        model.onIntent(ReaderContract.Intent.SaveHighlightNote("批注内容"))
        runCurrent()
        assertEquals(null, states.last().editingHighlightNoteId)
        assertEquals("批注内容", states.last().highlightList.single().note)
        assertEquals("批注内容", repo.store["w:c1:10-20"]?.note)
        // 空串清除。
        model.onIntent(ReaderContract.Intent.StartEditHighlightNote("w:c1:10-20"))
        runCurrent()
        model.onIntent(ReaderContract.Intent.SaveHighlightNote("   "))
        runCurrent()
        assertEquals(null, states.last().highlightList.single().note)
    }

    @Test
    fun novelSelectionUpdatesAndCommits() = runTest(dispatcher) {
        val repo = FakeHighlights()
        val model = ReaderViewModel(
            workId = "w",
            chapterId = "c1",
            machine = ReaderStateMachine(),
            chapters = TextProvider(),
            saveProgress = SaveReadingProgressUseCase(FakeProgress()),
            loadProgress = LoadReadingProgressUseCase(FakeProgress()),
            pages = LoadPageBytesUseCase(StubImages()),
            domains = emptySet(),
            highlights = repo,
        )
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        // 拖拽更新选区（逆序自动归一化）。
        model.onIntent(ReaderContract.Intent.UpdateNovelSelection(20, 12))
        runCurrent()
        assertEquals(12..20, states.last().selectionRange)
        assertTrue(repo.store.isEmpty())
        // 提交落盘并清选区（摘要取上下文窗口）。
        model.onIntent(ReaderContract.Intent.CommitNovelSelection)
        runCurrent()
        assertEquals(null, states.last().selectionRange)
        assertEquals(listOf("w:c1:12-21"), states.last().highlightList.map { it.id })
        assertTrue(repo.store.containsKey("w:c1:12-21"))
        // 空选区提交是空操作。
        model.onIntent(ReaderContract.Intent.CommitNovelSelection)
        runCurrent()
        assertEquals(1, states.last().highlightList.size)
        // 取消清除。
        model.onIntent(ReaderContract.Intent.UpdateNovelSelection(1, 5))
        runCurrent()
        model.onIntent(ReaderContract.Intent.ClearNovelSelection)
        runCurrent()
        assertEquals(null, states.last().selectionRange)
    }

    @Test
    fun typographyIntentsUpdateSettings() = runTest(dispatcher) {
        val model = vm(FakeProgress())
        val states = mutableListOf<ReaderContract.State>()
        backgroundScope.launch { model.state.collect { states += it } }
        runCurrent()
        model.onIntent(ReaderContract.Intent.NovelFontSizeChanged(24f))
        model.onIntent(ReaderContract.Intent.NovelLineHeightChanged(1.8f))
        model.onIntent(ReaderContract.Intent.NovelParagraphSpacingChanged(16f))
        model.onIntent(ReaderContract.Intent.NovelPageMarginChanged(24f))
        model.onIntent(ReaderContract.Intent.NovelParagraphIndentChanged(2.5f))
        runCurrent()
        val t = states.last().settings.typography
        assertEquals(24f, t.fontSizeSp)
        assertEquals(1.8f, t.lineHeightMultiple)
        assertEquals(16f, t.paragraphSpacingDp)
        assertEquals(24f, t.pageMarginDp)
        assertEquals(2.5f, t.paragraphIndentEm)
    }
}
