package feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import core.common.Debouncer
import core.model.NovelContentParser
import core.model.NovelDocument
import core.model.ReadingProgress
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ImageScaleType
import core.reader.ReaderPageRequest
import core.reader.ReaderSettingsStore
import core.reader.ReaderState
import core.reader.ReaderStateMachine
import core.reader.ReadingConfig
import core.reader.ReadingDirection
import core.reader.ReadingMode
import core.reader.ReaderTheme
import core.reader.TapZoneMode
import data.cache.ByteBudgetLru
import data.images.ImageFetcher
import data.images.ImageLoadingConfig
import data.images.CropBorders
import data.images.ImageRequest
import data.images.PageImageInfo
import data.repository.BookmarkRepository
import data.repository.HighlightRepository
import data.repository.NoopBookmarkRepository
import data.repository.NoopHighlightRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

/**
 * 阅读器 ViewModel（MVI：StateFlow 状态 + Channel 一次性事件）。
 *
 * 纪律：
 * - 页码/总数一律读机器同步快照，不读屏 State 推导；
 * - 保存走显式参数；虚拟时间断言必须推过期限（499/+5）；
 * - 图片字节只保留当前预加载窗口，窗外完成直接丢弃；
 * - 页请求先经 `ChapterProvider.resolvePage`（onImageLoad），再走统一图片出口。
 */
class ReaderViewModel(
    private val workId: String,
    private val chapterId: String,
    private val machine: ReaderStateMachine = ReaderStateMachine(),
    private val chapters: ChapterProvider,
    private val saveProgress: SaveReadingProgressUseCase,
    private val loadProgress: LoadReadingProgressUseCase,
    private val pages: LoadPageBytesUseCase,
    private val domains: Set<String>,
    workScope: CoroutineScope? = null,
    private val settingsStore: ReaderSettingsStore = DefaultReaderSettingsStore,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val pageCacheBytes: Long = 32L * 1024 * 1024,
    private val maxConcurrentPageLoads: Int = 3,
    private val mangaTitle: String = "",
    /** 书签持久化（默认 Noop，保证既有调用方零改动）。 */
    private val bookmarks: BookmarkRepository = NoopBookmarkRepository,
    /** 划线持久化（默认 Noop，保证既有调用方零改动）。 */
    private val highlights: HighlightRepository = NoopHighlightRepository,
) : ViewModel() {

    private data class Local(
        val mode: ReadingMode = ReadingMode.PAGED,
        val reachedEnd: Boolean = false,
        val pages: Map<Int, ByteArray> = emptyMap(),
        val pageSizes: Map<Int, core.reader.PageSize> = emptyMap(),
        val pdfBytes: ByteArray? = null,
        val pageStates: Map<Int, ReaderContract.PageLoadState> = emptyMap(),
        val article: String? = null,
        /** 结构化小说正文（M4；漫画为 null）。 */
        val novel: NovelDocument? = null,
        /** 从进度恢复的字符锚点，交给 Viewer 首次排版后定位。 */
        val novelAnchor: Int = 0,
        /** 当前位置的字符锚点（写入进度）。 */
        val savedCharOffset: Int = -1,
        val layoutKey: String = "",
        /** Viewer 回传的虚拟页数（机器页数未知时用于展示与翻页上限）。 */
        val novelPageCount: Int = 0,
        /** 小说文内图片业务头（key=effectiveUrl），随正文一起发布。 */
        val novelImageHeaders: Map<String, Map<String, String>> = emptyMap(),
        val settings: ReadingConfig = ReadingConfig(),
        val menuVisible: Boolean = false,
        val chapterListVisible: Boolean = false,
        val settingsVisible: Boolean = false,
        /** 已点亮书签的章节 id（持久化随 BookmarkRepository 落地）。 */
        val bookmarks: Set<String> = emptySet(),
        /** 本作品书签列表（面板打开时加载）。 */
        val bookmarkList: List<core.model.Bookmark> = emptyList(),
        /** 书签面板可见性。 */
        val bookmarksVisible: Boolean = false,
        /** 当前设置作用域（打开章节时进入 `book:<workId>`）。 */
        val bookScope: String = "global",
        /** 正在编辑笔记的书签 id。 */
        val editingNoteId: String? = null,
        /** 正在编辑笔记的划线 id。 */
        val editingHighlightNoteId: String? = null,
        /** 拖拽选区（文档坐标闭区间；预选中，未落盘）。 */
        val selectionRange: IntRange? = null,
        /** 划线模式（开时点段落即标/取消，不翻页）。 */
        val highlightMode: Boolean = false,
        /** 当前章节划线（打开时加载）。 */
        val highlightList: List<core.model.Highlight> = emptyList(),
        /** 同章内书签定位请求（Viewer 消费后经 SeekHandled 回收；搜索跳转复用）。 */
        val bookmarkSeek: ReaderContract.BookmarkSeek? = null,
        /** 全文搜索面板可见性。 */
        val searchVisible: Boolean = false,
        /** 全文搜索关键字。 */
        val searchQuery: String = "",
        /** 全文搜索命中（规范化全文字符区间）。 */
        val searchMatches: List<IntRange> = emptyList(),
        /** 当前高亮命中的下标。 */
        val searchIndex: Int = 0,
    )

    private val _effect = Channel<ReaderContract.Effect>(Channel.BUFFERED)
    val effect: Flow<ReaderContract.Effect> = _effect.receiveAsFlow()

    private val local = MutableStateFlow(
        Local(
            mode = settingsStore.settings.value.mode,
            settings = settingsStore.settings.value,
        ),
    )

    private val worker: CoroutineScope = workScope ?: viewModelScope

    val state: StateFlow<ReaderContract.State> =
        combine(machine.state, local) { ms, l ->
            when (ms) {
                is ReaderState.Idle ->
                    baseState(l, loading = true)
                is ReaderState.ResolvingChapter, is ReaderState.Loading ->
                    baseState(
                        l,
                        chapterId = msChapter(ms),
                        loading = true,
                    )
                is ReaderState.Ready ->
                    baseState(
                        l,
                        chapterId = ms.chapterId,
                        pageIndex = 0,
                        pageCount = ms.pageCount,
                        loading = false,
                    )
                is ReaderState.Reading ->
                    baseState(
                        l,
                        chapterId = ms.chapterId,
                        pageIndex = ms.pageIndex,
                        pageCount = machine.currentPageCount(),
                        loading = false,
                        reachedEnd = l.reachedEnd,
                    )
                is ReaderState.Preloading ->
                    baseState(
                        l,
                        chapterId = ms.chapterId,
                        pageIndex = ms.pageIndex,
                        pageCount = machine.currentPageCount(),
                        loading = false,
                    )
                is ReaderState.ChapterEnd ->
                    baseState(
                        l,
                        chapterId = ms.chapterId,
                        loading = false,
                        reachedEnd = true,
                    )
                is ReaderState.Error ->
                    baseState(
                        l,
                        chapterId = chapterId,
                        loading = false,
                        error = ms.message,
                    )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderContract.State(workId = workId))

    private fun baseState(
        l: Local,
        chapterId: String = machine.currentChapterId() ?: this.chapterId,
        pageIndex: Int = 0,
        pageCount: Int = machine.currentPageCount(),
        loading: Boolean = false,
        error: String? = null,
        reachedEnd: Boolean = l.reachedEnd,
    ): ReaderContract.State = ReaderContract.State(
        workId = workId,
        chapterId = chapterId,
        chapterTitle = machine.currentChapter()?.title.orEmpty(),
        chapterIndex = machine.chapterIndex().coerceAtLeast(0),
        chapterCount = machine.chapterCount(),
        pageIndex = pageIndex,
        pageCount = if (l.novel != null && l.novelPageCount > 0) l.novelPageCount else pageCount,
        mode = l.mode,
        loading = loading,
        error = error,
        reachedEnd = reachedEnd,
        pages = l.pages,
        pdfBytes = l.pdfBytes,
        pageStates = l.pageStates,
        pageSizes = l.pageSizes,
        article = l.article,
        novel = l.novel,
        novelAnchor = l.novelAnchor,
        novelImageHeaders = l.novelImageHeaders,
        novelLayoutKey = l.layoutKey,
        chapters = machine.chapters(),
        previousChapterTitle = machine.chapters().getOrNull(machine.chapterIndex() - 1)?.title,
        nextChapterTitle = machine.chapters().getOrNull(machine.chapterIndex() + 1)?.title,
        bookmarked = (machine.currentChapterId() ?: chapterId) in l.bookmarks,
        highlightMode = l.highlightMode,
        highlightList = l.highlightList,
        bookmarkList = l.bookmarkList,
        bookmarksVisible = l.bookmarksVisible,
        settingsScope = l.bookScope,
        editingNoteId = l.editingNoteId,
        editingHighlightNoteId = l.editingHighlightNoteId,
        selectionRange = l.selectionRange,
        bookmarkSeek = l.bookmarkSeek,
        searchVisible = l.searchVisible,
        searchQuery = l.searchQuery,
        searchMatches = l.searchMatches,
        searchIndex = l.searchIndex,
        mangaTitle = mangaTitle,
        settings = l.settings,
        menuVisible = l.menuVisible,
        chapterListVisible = l.chapterListVisible,
        settingsVisible = l.settingsVisible,
        hasPreviousChapter = machine.hasPreviousChapter(),
        hasNextChapter = machine.hasNextChapter(),
    )

    private val saver = Debouncer(worker, 500) {
        val w = workId
        val c = machine.currentChapterId() ?: return@Debouncer
        saveNow(w, c, machine.currentPageIndex(), machine.currentPageCount())
    }
    private var endNotifiedFor: String? = null
    private var windowVersion = 0
    private var windowChapter: String? = null
    private var prefetchingChapter: String? = null
    /** 跨章书签跳转的待定位（chapterId → position），目标章 onReady 时作为锚点消费。 */
    private var pendingBookmarkSeek: Pair<String, Int>? = null
    private val pageJobs = mutableMapOf<Int, Job>()
    private var autoScrollJob: Job? = null

    override fun onCleared() {
        autoScrollJob?.cancel()
        super.onCleared()
    }
    private val pageCache = ByteBudgetLru<ByteArray>(pageCacheBytes)
    private val pagePermits = Semaphore(maxConcurrentPageLoads)

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { cfg ->
                local.update { it.copy(settings = cfg, mode = cfg.mode) }
                machine.setConfig(cfg)
            }
        }
        viewModelScope.launch {
            machine.state.collect { ms ->
                if (ms is ReaderState.Ready) onReady(ms.workId, ms.chapterId, ms.pageCount)
                if (ms is ReaderState.Reading) ensureWindow()
                if (ms is ReaderState.ChapterEnd && endNotifiedFor != ms.chapterId) {
                    endNotifiedFor = ms.chapterId
                    local.update { it.copy(reachedEnd = true) }
                    _effect.send(ReaderContract.Effect.Toast("已是最后一章"))
                }
            }
        }
        viewModelScope.launch {
            settingsStore.settings
                .map { Triple(it.autoScroll, it.autoScrollIntervalSec, it.mode) }
                .distinctUntilChanged()
                .collect { (enabled, intervalSec, mode) ->
                    autoScrollJob?.cancel()
                    if (enabled && mode == ReadingMode.PAGED) {
                        autoScrollJob = viewModelScope.launch {
                            while (true) {
                                delay((intervalSec * 1000).toLong().coerceAtLeast(500))
                                if (!local.value.menuVisible) {
                                    onIntent(ReaderContract.Intent.NextPage)
                                }
                            }
                        }
                    }
                }
        }
        viewModelScope.launch { open() }
    }

    fun onIntent(intent: ReaderContract.Intent) {
        when (intent) {
            is ReaderContract.Intent.PageChanged -> {
                // 同页重发（collector 重建时的初始发射）不关菜单；
                // 真正翻页才收起（对齐 Komikku 翻页隐藏菜单）。
                val changed = intent.index != machine.currentPageIndex()
                machine.pageTo(intent.index)
                if (changed && local.value.menuVisible) {
                    local.update { it.copy(menuVisible = false) }
                }
                ensureWindow()
                prefetchNextChapter()
                saver.submit()
            }
            is ReaderContract.Intent.NextPage -> {
                if (local.value.menuVisible) {
                    local.update { it.copy(menuVisible = false) }
                    return
                }
                val next = machine.currentPageIndex() + 1
                val total = machine.currentPageCount()
                if (next < total) {
                    machine.pageTo(next)
                    ensureWindow()
                    prefetchNextChapter()
                    saver.submit()
                } else if (machine.hasNextChapter()) {
                    launchNextChapter()
                } else {
                    toast("已是本章最后一页")
                }
            }
            is ReaderContract.Intent.PrevPage -> {
                if (local.value.menuVisible) {
                    local.update { it.copy(menuVisible = false) }
                    return
                }
                val prev = machine.currentPageIndex() - 1
                if (prev >= 0) {
                    machine.pageTo(prev)
                    ensureWindow()
                    saver.submit()
                } else if (machine.hasPreviousChapter()) {
                    viewModelScope.launch { machine.prevChapter() }
                } else {
                    toast("已是本章第一页")
                }
            }
            is ReaderContract.Intent.NextChapter -> launchNextChapter()
            is ReaderContract.Intent.PrevChapter -> viewModelScope.launch {
                val prevId = machine.previousChapterId()
                if (prevId != null && isPdfChapter(prevId)) {
                    openPdf(prevId)
                } else if (!machine.prevChapter()) {
                    _effect.send(ReaderContract.Effect.Toast("已是第一章"))
                }
            }
            is ReaderContract.Intent.RetryClicked -> {
                val id = machine.currentChapterId() ?: chapterId
                if (isPdfChapter(id) && local.value.pdfBytes == null) {
                    viewModelScope.launch { openPdf(id) }
                } else {
                    viewModelScope.launch { machine.retry() }
                }
            }
            is ReaderContract.Intent.RefreshPage -> {
                dropWindow()
                ensureWindow()
            }
            is ReaderContract.Intent.ModeChanged -> {
                settingsStore.update { it.copy(mode = intent.mode) }
                local.update { it.copy(mode = intent.mode) }
            }
            is ReaderContract.Intent.DirectionChanged -> settingsStore.update {
                it.copy(direction = intent.direction)
            }
            is ReaderContract.Intent.ScaleChanged -> settingsStore.update {
                it.copy(scaleType = intent.scaleType)
            }
            is ReaderContract.Intent.TapZoneChanged -> settingsStore.update {
                it.copy(tapZone = intent.mode)
            }
            is ReaderContract.Intent.ThemeChanged -> settingsStore.update {
                it.copy(theme = intent.theme)
            }
            is ReaderContract.Intent.ToggleDoublePage -> settingsStore.update {
                it.copy(doublePage = !it.doublePage)
            }
            is ReaderContract.Intent.ToggleGrayscale -> settingsStore.update {
                it.copy(grayscale = !it.grayscale)
            }
            is ReaderContract.Intent.TogglePageNumber -> settingsStore.update {
                it.copy(showPageNumber = !it.showPageNumber)
            }
            is ReaderContract.Intent.WebtoonPaddingChanged -> settingsStore.update {
                it.copy(webtoonSidePaddingPercent = intent.percent.coerceIn(0, 25))
            }
            is ReaderContract.Intent.DimLevelChanged -> settingsStore.update {
                it.copy(dimLevel = intent.percent.coerceIn(0, 60))
            }
            is ReaderContract.Intent.TapInvertChanged -> settingsStore.update {
                it.copy(tapInvert = intent.mode)
            }
            is ReaderContract.Intent.ToggleSmallerTapZone -> settingsStore.update {
                it.copy(smallerTapZone = !it.smallerTapZone)
            }
            is ReaderContract.Intent.ToggleInvertDoublePages -> settingsStore.update {
                it.copy(invertDoublePages = !it.invertDoublePages)
            }
            is ReaderContract.Intent.ToggleVolumeKeys -> settingsStore.update {
                it.copy(volumeKeysEnabled = !it.volumeKeysEnabled)
            }
            is ReaderContract.Intent.ToggleVolumeKeysInverted -> settingsStore.update {
                it.copy(volumeKeysInverted = !it.volumeKeysInverted)
            }
            is ReaderContract.Intent.ToggleReadWithLongTap -> settingsStore.update {
                it.copy(readWithLongTap = !it.readWithLongTap)
            }
            is ReaderContract.Intent.ToggleInvertedColors -> settingsStore.update {
                it.copy(invertedColors = !it.invertedColors)
            }
            is ReaderContract.Intent.ToggleAutoScroll -> settingsStore.update {
                it.copy(autoScroll = !it.autoScroll)
            }
            is ReaderContract.Intent.AutoScrollIntervalChanged -> settingsStore.update {
                it.copy(autoScrollIntervalSec = intent.seconds.coerceIn(1f, 10f))
            }
            is ReaderContract.Intent.TintColorChanged -> settingsStore.update {
                it.copy(tintColorArgb = intent.argb)
            }
            is ReaderContract.Intent.ToggleFlashOnChange -> settingsStore.update {
                it.copy(flashOnPageChange = !it.flashOnPageChange)
            }
            is ReaderContract.Intent.FlashDurationChanged -> settingsStore.update {
                it.copy(flashDurationMs = intent.millis.coerceIn(100, 1500))
            }
            is ReaderContract.Intent.ToggleFlashWhite -> settingsStore.update {
                it.copy(flashWhite = !it.flashWhite)
            }
            is ReaderContract.Intent.ToggleCropBorders -> settingsStore.update {
                it.copy(cropBorders = !it.cropBorders)
            }
            is ReaderContract.Intent.OpenBookmarks -> {
                local.update { it.copy(bookmarksVisible = true) }
                viewModelScope.launch {
                    val list = runCatching { bookmarks.listForWork(workId) }.getOrDefault(emptyList())
                    local.update { it.copy(bookmarkList = list) }
                }
            }
            is ReaderContract.Intent.CloseBookmarks ->
                local.update { it.copy(bookmarksVisible = false) }
            is ReaderContract.Intent.StartEditNote ->
                local.update { it.copy(editingNoteId = intent.id) }
            is ReaderContract.Intent.CancelEditNote ->
                local.update { it.copy(editingNoteId = null) }
            is ReaderContract.Intent.StartEditHighlightNote ->
                local.update { it.copy(editingHighlightNoteId = intent.id) }
            is ReaderContract.Intent.CancelEditHighlightNote ->
                local.update { it.copy(editingHighlightNoteId = null) }
            is ReaderContract.Intent.SaveHighlightNote -> viewModelScope.launch {
                val id = local.value.editingHighlightNoteId ?: return@launch
                val current = local.value.highlightList.firstOrNull { it.id == id }
                    ?: runCatching { highlights.list(workId, machine.currentChapterId().orEmpty()) }
                        .getOrNull()?.firstOrNull { it.id == id }
                if (current == null) {
                    local.update { it.copy(editingHighlightNoteId = null) }
                    return@launch
                }
                // 笔记本地展示用，上限 2000 字（数据最小化）；空串视为清除。
                val updated = current.copy(note = intent.note.take(2000).ifBlank { null })
                runCatching { highlights.upsert(updated) }
                local.update {
                    it.copy(
                        editingHighlightNoteId = null,
                        highlightList = it.highlightList.map { if (it.id == id) updated else it },
                    )
                }
            }
            is ReaderContract.Intent.SaveNote -> viewModelScope.launch {
                val id = local.value.editingNoteId ?: return@launch
                val current = local.value.bookmarkList.firstOrNull { it.id == id }
                    ?: runCatching { bookmarks.listForWork(workId) }.getOrNull()
                        ?.firstOrNull { it.id == id }
                if (current == null) {
                    local.update { it.copy(editingNoteId = null) }
                    return@launch
                }
                // 笔记本地展示用，上限 2000 字（数据最小化）；空串视为清除。
                val updated = current.copy(note = intent.note.take(2000).ifBlank { null })
                runCatching { bookmarks.upsert(updated) }
                local.update {
                    it.copy(
                        editingNoteId = null,
                        bookmarkList = it.bookmarkList.map { if (it.id == id) updated else it },
                    )
                }
            }
            is ReaderContract.Intent.JumpToBookmark -> {
                val target = local.value.bookmarkList.firstOrNull { it.id == intent.id }
                    ?: return
                local.update { it.copy(bookmarksVisible = false) }
                val current = machine.currentChapterId()
                if (target.chapterId == current) {
                    val doc = local.value.novel
                    if (doc != null) {
                        local.update {
                            it.copy(bookmarkSeek = ReaderContract.BookmarkSeek(target.position, nowMs()))
                        }
                    } else {
                        machine.pageTo(target.position.coerceAtLeast(0))
                        ensureWindow()
                        prefetchNextChapter()
                        saver.submit()
                    }
                } else {
                    pendingBookmarkSeek = target.chapterId to target.position
                    viewModelScope.launch { openChapterRoute(target.chapterId, first = false) }
                }
            }
            is ReaderContract.Intent.BookmarkSeekHandled -> {
                if (local.value.bookmarkSeek?.nonce == intent.nonce) {
                    local.update { it.copy(bookmarkSeek = null) }
                }
            }
            is ReaderContract.Intent.JumpToHighlight -> {
                val target = local.value.highlightList.firstOrNull { it.id == intent.id }
                    ?: return
                local.update { it.copy(bookmarksVisible = false) }
                local.update {
                    it.copy(bookmarkSeek = ReaderContract.BookmarkSeek(target.start, nowMs()))
                }
            }
            is ReaderContract.Intent.RemoveHighlight -> {
                local.update { it.copy(highlightList = it.highlightList.filterNot { it.id == intent.id }) }
                viewModelScope.launch { runCatching { highlights.remove(intent.id) } }
            }
            is ReaderContract.Intent.ToggleHighlightMode -> {
                val on = !local.value.highlightMode
                // 退出划线模式时丢弃未提交的选区（避免无模式残留）。
                local.update { it.copy(highlightMode = on, selectionRange = if (on) it.selectionRange else null) }
                if (on) toast("划线模式开：点段落即可标记")
            }
            is ReaderContract.Intent.UpdateNovelSelection -> {
                val s = intent.start
                val e = intent.end
                local.update {
                    it.copy(selectionRange = if (s == e) null else IntRange(minOf(s, e), maxOf(s, e)))
                }
            }
            is ReaderContract.Intent.ClearNovelSelection ->
                local.update { it.copy(selectionRange = null) }
            is ReaderContract.Intent.CommitNovelSelection -> viewModelScope.launch {
                val range = local.value.selectionRange ?: return@launch
                val c = machine.currentChapterId() ?: return@launch
                if (range.last <= range.first) {
                    local.update { it.copy(selectionRange = null) }
                    return@launch
                }
                val id = "$workId:$c:${range.first}-${range.last + 1}"
                val item = core.model.Highlight(
                    id = id,
                    workId = workId,
                    chapterId = c,
                    start = range.first,
                    end = range.last + 1,
                    excerpt = excerptAround(range.first, range.last + 1),
                    createdAtEpochMs = nowMs(),
                )
                runCatching { highlights.upsert(item) }
                val fresh = runCatching { highlights.list(workId, c) }.getOrDefault(local.value.highlightList)
                local.update { it.copy(highlightList = fresh, selectionRange = null) }
                toast("已标出所选")
            }
            is ReaderContract.Intent.ToggleBlockHighlight -> {
                // 乐观更新：状态同步切换保证连点语义正确，落盘异步追赶（失败则下次打开按落盘值恢复）。
                val c = machine.currentChapterId() ?: return
                val start = intent.charStart.coerceAtLeast(0)
                val end = intent.charEnd.coerceAtLeast(start + 1)
                val id = "$workId:$c:$start-$end"
                val existing = local.value.highlightList.firstOrNull { it.id == id }
                if (existing != null) {
                    local.update { it.copy(highlightList = it.highlightList.filterNot { it.id == id }) }
                    viewModelScope.launch { runCatching { highlights.remove(id) } }
                } else {
                    val item = core.model.Highlight(
                        id = id,
                        workId = workId,
                        chapterId = c,
                        start = start,
                        end = end,
                        excerpt = excerptAround(start, end),
                        createdAtEpochMs = nowMs(),
                    )
                    local.update { it.copy(highlightList = it.highlightList + item) }
                    viewModelScope.launch { runCatching { highlights.upsert(item) } }
                }
            }
            is ReaderContract.Intent.OpenSearch ->
                local.update { it.copy(searchVisible = true) }
            is ReaderContract.Intent.CloseSearch ->
                local.update {
                    it.copy(
                        searchVisible = false,
                        searchQuery = "",
                        searchMatches = emptyList(),
                        searchIndex = 0,
                    )
                }
            is ReaderContract.Intent.SearchQueryChanged ->
                updateSearch(intent.query)
            is ReaderContract.Intent.SearchNext ->
                stepSearch(+1)
            is ReaderContract.Intent.SearchPrev ->
                stepSearch(-1)
            is ReaderContract.Intent.HighlightAllSearchMatches -> viewModelScope.launch {
                val matches = local.value.searchMatches
                if (matches.isEmpty()) {
                    toast("没有可标记的命中")
                    return@launch
                }
                val full = normalizedFullText()
                var added = 0
                for (m in matches) {
                    val id = "$workId:$chapterId:${m.first}-${m.last + 1}"
                    if (local.value.highlightList.any { it.id == id }) continue
                    val item = core.model.Highlight(
                        id = id,
                        workId = workId,
                        chapterId = chapterId,
                        start = m.first,
                        end = m.last + 1,
                        excerpt = full.substring(m.first, m.last + 1).takeIf { it.isNotEmpty() },
                        createdAtEpochMs = nowMs(),
                    )
                    runCatching { highlights.upsert(item) }
                    added++
                }
                val fresh = runCatching { highlights.list(workId, chapterId) }.getOrDefault(emptyList())
                local.update { it.copy(highlightList = fresh) }
                toast(if (added > 0) "已标出 $added 处" else "所选范围已全部标记")
            }
            is ReaderContract.Intent.ToggleBookmark -> {
                val id = machine.currentChapterId() ?: chapterId
                viewModelScope.launch {
                    if (id in local.value.bookmarks) {
                        runCatching { bookmarks.removeByChapter(workId, id) }
                        local.update { it.copy(bookmarks = it.bookmarks - id) }
                    } else {
                        val position = currentBookmarkPosition()
                        runCatching {
                            bookmarks.upsert(
                                core.model.Bookmark(
                                    id = "$workId:$id",
                                    workId = workId,
                                    chapterId = id,
                                    position = position,
                                    excerpt = bookmarkExcerpt(position),
                                    createdAtEpochMs = nowMs(),
                                ),
                            )
                        }
                        local.update { it.copy(bookmarks = it.bookmarks + id) }
                    }
                }
            }
            is ReaderContract.Intent.ToggleMenu -> local.update { it.copy(menuVisible = !it.menuVisible) }
            is ReaderContract.Intent.CloseMenu -> local.update { it.copy(menuVisible = false) }
            is ReaderContract.Intent.OpenChapterList -> local.update {
                it.copy(menuVisible = false, chapterListVisible = true)
            }
            is ReaderContract.Intent.CloseChapterList -> local.update { it.copy(chapterListVisible = false) }
            is ReaderContract.Intent.OpenSettings -> local.update {
                it.copy(menuVisible = false, settingsVisible = true)
            }
            is ReaderContract.Intent.CloseSettings -> local.update { it.copy(settingsVisible = false) }
            is ReaderContract.Intent.SelectChapter -> {
                local.update { it.copy(chapterListVisible = false, menuVisible = false, reachedEnd = false) }
                viewModelScope.launch { openChapterRoute(intent.chapterId, first = false) }
            }
            is ReaderContract.Intent.RetryPage -> {
                local.update {
                    it.copy(
                        pages = it.pages - intent.index,
                        pageStates = it.pageStates - intent.index,
                    )
                }
                pageJobs.remove(intent.index)?.cancel()
                ensureWindow()
            }
            is ReaderContract.Intent.NovelPagesReady -> {
                machine.setNovelPageCount(intent.pageCount.coerceAtLeast(1))
                local.update {
                    it.copy(
                        novelPageCount = intent.pageCount.coerceAtLeast(1),
                        layoutKey = intent.layoutKey,
                    )
                }
            }
            is ReaderContract.Intent.NovelAnchorChanged -> {
                local.update {
                    it.copy(savedCharOffset = intent.charOffset, layoutKey = intent.layoutKey)
                }
                saver.submit()
            }
            is ReaderContract.Intent.NovelFontSizeChanged -> settingsStore.update {
                it.copy(typography = it.typography.copy(fontSizeSp = intent.sp))
            }
            is ReaderContract.Intent.NovelLineHeightChanged -> settingsStore.update {
                it.copy(typography = it.typography.copy(lineHeightMultiple = intent.multiple))
            }
            is ReaderContract.Intent.NovelParagraphSpacingChanged -> settingsStore.update {
                it.copy(typography = it.typography.copy(paragraphSpacingDp = intent.dp))
            }
            is ReaderContract.Intent.NovelPageMarginChanged -> settingsStore.update {
                it.copy(typography = it.typography.copy(pageMarginDp = intent.dp))
            }
            is ReaderContract.Intent.NovelParagraphIndentChanged -> settingsStore.update {
                it.copy(typography = it.typography.copy(paragraphIndentEm = intent.em))
            }
            is ReaderContract.Intent.ResetBookSettings -> {
                val scope = "book:$workId"
                settingsStore.resetScope(scope)
                settingsStore.enterScope(scope)
            }
        }
    }

    private fun launchNextChapter() {
        if (machine.state.value is ReaderState.Loading) return
        viewModelScope.launch {
            endNotifiedFor = null
            local.update { it.copy(reachedEnd = false) }
            // 跨格式：下一章是 PDF 则走专用打开（machine 页管线不处理 PDF）。
            val nextId = machine.nextChapterId()
            if (nextId != null && isPdfChapter(nextId)) {
                openPdf(nextId)
                return@launch
            }
            dropWindow()
            machine.nextChapter()
        }
    }

    private suspend fun open() {
        openChapterRoute(chapterId, first = true)
    }

    /** PDF 按扩展名识别（`xxx.pdf`），走专用阅读器，不进页管线。 */
    private fun isPdfChapter(id: String): Boolean =
        id.substringAfterLast('.', "").equals("pdf", ignoreCase = true)

    private suspend fun openChapterRoute(id: String, first: Boolean) {
        // 按书设置作用域：同一作品内各章节共享，跨书隔离；平台未注入持久化时为内存态。
        val scope = "book:$workId"
        settingsStore.enterScope(scope)
        local.update { it.copy(bookScope = scope) }
        if (isPdfChapter(id)) {
            openPdf(id)
            return
        }
        local.update { it.copy(pdfBytes = null) }
        if (first) {
            machine.openChapter(workId, id, chapters)
        } else {
            dropWindow()
            viewModelScope.launch { machine.jumpToChapter(id) }
        }
    }

    private suspend fun openPdf(id: String) {
        dropWindow()
        local.update { it.copy(pdfBytes = null) }
        try {
            // 先确立 machine 章节（Ready 会触发 onReady→ensureWindow 的二次 dropWindow），
            // 再写字节并同步 windowChapter，避免字节被滞后的窗口清空。
            machine.openChapterListOnly(workId, id, chapters)
            val bytes = chapters.rawBytes(workId, id)
            windowChapter = id
            local.update { it.copy(pdfBytes = bytes) }
        } catch (e: Exception) {
            machine.fail("PDF 打开失败：${e.message}", true)
        }
    }

    private suspend fun onReady(w: String, c: String, pageCount: Int) {
        val saved = runCatching { loadProgress(w) }.getOrNull()
        val sameChapter = saved?.chapterId == c
        val savedPage = saved?.pageIndex
        val savedAnchorOffset = saved?.charOffset ?: -1
        val savedLayoutKey = saved?.layoutKey.orEmpty()
        var idx = 0
        // 有字符锚点时交给 Viewer 按新分页结果定位；否则按旧虚拟页恢复（漫画/旧进度）。
        if (sameChapter && savedPage != null && savedAnchorOffset < 0) {
            idx = savedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            machine.pageTo(idx)
        }
        val payload = machine.currentNovelPayload()
        val rawDoc = payload?.let { NovelContentParser.parse(c, it.body, it.format) }
        // 小说文内图片运行时重写（`novel.onImageResolve` 闭环）：
        // 对 IMAGE 块逐一经 ChapterProvider.resolveNovelImage 取源重写，失败回退原文；
        // 业务头（Referer 类，已脱敏）随 State.novelImageHeaders 发布，Viewer 取图时带上；
        // 最终是否可加载仍由 NovelReader.isAllowedNovelImageUrl（https/本地 `!/`）与
        // SourceImageFetcher 的 UrlPolicy 白名单 + 逐跳复核决定，本层不做白名单放行。
        val resolved = rawDoc?.let { resolveNovelImages(it) }
        val doc = resolved?.first
        val imageHeaders = resolved?.second.orEmpty()
        // 章节划线随正文一起恢复（失败不阻断阅读）。
        val highlightItems = runCatching { highlights.list(workId, c) }.getOrDefault(emptyList())
        // 跨章书签跳转优先于进度恢复：用户显式点选书签即以书签位置为锚点。
        val pending = pendingBookmarkSeek?.takeIf { it.first == c }?.second
        if (pending != null) pendingBookmarkSeek = null
        val anchor = pending ?: if (sameChapter) savedAnchorOffset.coerceAtLeast(0) else 0
        // 书签恢复：本章有落盘书签则点亮（定位跳转待书签列表 UI）。
        val bookmarkedNow = runCatching { bookmarks.findByChapter(w, c) }.getOrNull() != null
        local.update {
            it.copy(
                article = machine.currentText(),
                novel = doc,
                bookmarks = if (bookmarkedNow) it.bookmarks + c else it.bookmarks - c,
                novelAnchor = anchor,
                novelImageHeaders = imageHeaders,
                savedCharOffset = if (sameChapter) savedAnchorOffset else -1,
                highlightList = highlightItems,
                // 搜索命中是文档坐标，切章后失效：清空查询与未消费的定位，避免误跳新章。
                searchQuery = "",
                searchMatches = emptyList(),
                searchIndex = 0,
                bookmarkSeek = null,
                layoutKey = if (sameChapter) savedLayoutKey else "",
                novelPageCount = 0,
                reachedEnd = false,
                pageStates = emptyMap(),
            )
        }
        ensureWindow()
        saveNow(w, c, idx, pageCount)
    }

    /** 小说文内图片批量重写：IMAGE 块经 provider 取有效 URL + 业务头，单张失败不阻断整章。 */
    private suspend fun resolveNovelImages(doc: core.model.NovelDocument): Pair<core.model.NovelDocument, Map<String, Map<String, String>>> {
        if (doc.blocks.none { it.kind == core.model.NovelBlockKind.IMAGE }) return doc to emptyMap()
        val headers = mutableMapOf<String, Map<String, String>>()
        val rewritten = doc.blocks.map { block ->
            if (block.kind != core.model.NovelBlockKind.IMAGE) return@map block
            val original = block.imageUrl ?: return@map block
            val request = runCatching { chapters.resolveNovelImage(original) }.getOrNull()
                ?: return@map block
            val effective = request.effectiveUrl.takeIf { it.isNotBlank() } ?: original
            if (request.headers.isNotEmpty()) headers[effective] = request.headers
            if (effective == original) block else block.copy(imageUrl = effective)
        }
        return doc.copy(blocks = rewritten) to headers
    }

    /** 书签位置：小说用字符锚点（重排后仍可定位），漫画用虚拟页下标。 */
    private fun currentBookmarkPosition(): Int {
        val doc = local.value.novel
        if (doc != null) {
            val saved = local.value.savedCharOffset
            if (saved >= 0) return saved
            return local.value.novelAnchor.coerceAtLeast(0)
        }
        return machine.currentPageIndex().coerceAtLeast(0)
    }

    /** 全文搜索上限（超长章截断，保证输入法每键回车可测）。 */
    private val maxSearchMatches: Int = 500

    /** 规范化全文（与分页锚点同坐标：各块 text 以 '\n' 连接）。 */
    private fun normalizedFullText(): String =
        local.value.novel?.blocks?.joinToString("\n") { it.text }.orEmpty()

    /** 关键字变更：重算命中并跳到当前锚点处及之后的首个命中。 */
    private fun updateSearch(query: String) {
        if (local.value.novel == null) {
            local.update {
                it.copy(searchQuery = query, searchMatches = emptyList(), searchIndex = 0)
            }
            return
        }
        val matches = findMatches(normalizedFullText(), query)
        val anchor = local.value.savedCharOffset.takeIf { it >= 0 }
            ?: local.value.novelAnchor
        val index = matches.indexOfFirst { it.first >= anchor }.takeIf { it >= 0 } ?: 0
        local.update {
            it.copy(
                searchQuery = query,
                searchMatches = matches,
                searchIndex = index,
                // 空结果时回收旧 seek，避免滚动到过期位置。
                bookmarkSeek = if (matches.isEmpty()) null else it.bookmarkSeek,
            )
        }
        if (matches.isNotEmpty()) {
            local.update {
                it.copy(bookmarkSeek = ReaderContract.BookmarkSeek(matches[index].first, nowMs()))
            }
        }
    }

    /** 上/下个命中（循环），每次经 seek 通道滚动。 */
    private fun stepSearch(delta: Int) {
        val matches = local.value.searchMatches
        if (matches.isEmpty()) return
        val size = matches.size
        val next = ((local.value.searchIndex + delta) % size + size) % size
        local.update {
            it.copy(
                searchIndex = next,
                bookmarkSeek = ReaderContract.BookmarkSeek(matches[next].first, nowMs()),
            )
        }
    }

    /** 字面子串命中（大小写不敏感），上限截断。 */
    private fun findMatches(full: String, query: String): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var from = 0
        while (out.size < maxSearchMatches) {
            val at = full.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            out += at until at + query.length
            from = at + query.length.coerceAtLeast(1)
        }
        return out
    }

    /** 指定字符区间的正文窗口（上限 120 字；本地展示用，不出设备）。 */
    private fun excerptAround(start: Int, end: Int): String? {
        val doc = local.value.novel ?: return null
        val full = doc.blocks.joinToString("\n") { it.text }
        if (full.isEmpty()) return null
        val from = (start - 30).coerceAtLeast(0)
        val to = (end + 30).coerceAtMost(full.length)
        if (to <= from) return null
        return full.substring(from, to).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /** 书签摘要：小说取锚点前后共 120 字正文窗口（本地展示用，不出设备）；漫画无摘要。 */
    private fun bookmarkExcerpt(position: Int): String? {
        val doc = local.value.novel ?: return null
        val full = doc.blocks.joinToString("\n") { it.text }
        if (full.isEmpty()) return null
        val center = position.coerceIn(0, full.length)
        val from = (center - 60).coerceAtLeast(0)
        val to = (center + 60).coerceAtMost(full.length)
        return full.substring(from, to).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 窗口保障：补齐缺页抓取、取消窗外任务、驱逐窗外字节、丢弃过期完成。
     * 与 `machine.preloadWindow()` 同源（前 1 后 2）；并发受 [pagePermits] 限制，
     * 字节同时进入跨章 LRU（重开章节不再重复下载）。
     */
    private fun ensureWindow() {
        val chapter = machine.currentChapterId() ?: return
        if (chapter != windowChapter) {
            dropWindow()
            windowChapter = chapter
        }
        val window = machine.preloadWindow().toSet()
        val urls = machine.currentPageUrls()
        windowVersion++
        val v = windowVersion
        val chapterForJob = chapter

        val stale = pageJobs.keys.filterNot { it in window }
        stale.forEach { pageJobs.remove(it)?.cancel() }

        val kept = local.value.pages.filterKeys { it in window }
        val keptStates = local.value.pageStates.filterKeys { it in window }
        val keptSizes = local.value.pageSizes.filterKeys { it in window }
        if (kept.size != local.value.pages.size || keptStates.size != local.value.pageStates.size ||
            keptSizes.size != local.value.pageSizes.size
        ) {
            local.update {
                it.copy(pages = kept, pageStates = keptStates, pageSizes = keptSizes)
            }
        }

        for (index in window) {
            if (index in local.value.pages || pageJobs.containsKey(index)) continue
            if (local.value.pageStates[index] is ReaderContract.PageLoadState.Loading) continue
            val url = urls.getOrNull(index) ?: continue
            local.update {
                it.copy(pageStates = it.pageStates + (index to ReaderContract.PageLoadState.Loading))
            }
            val job = worker.launch {
                val bytes = try {
                    pagePermits.withPermit {
                        val request = runCatching {
                            chapters.resolvePage(workId, chapterForJob, url)
                        }.getOrElse { ReaderPageRequest(url) }
                        val key = cacheKey(chapterForJob, request)
                        pageCache.get(key) ?: run {
                            val fetched = pages(request, domains)
                            // 自动裁边（设置门控；失败回退原字节，永不阻断阅读）。
                            val cropped = if (local.value.settings.cropBorders) {
                                runCatching { CropBorders.crop(fetched) }.getOrNull()
                            } else {
                                null
                            }
                            val final = cropped ?: fetched
                            pageCache.put(key, final, final.size.toLong())
                            final
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (v == windowVersion && windowChapter == chapterForJob) {
                        pageJobs.remove(index)
                        local.update {
                            it.copy(
                                pageStates = it.pageStates + (
                                    index to ReaderContract.PageLoadState.Error(
                                        e.message ?: "图片加载失败",
                                    )
                                    ),
                            )
                        }
                    }
                    return@launch
                }
                if (v != windowVersion || windowChapter != chapterForJob) {
                    pageJobs.remove(index)
                    return@launch
                }
                pageJobs.remove(index)
                val size = PageImageInfo.sizeOf(bytes)
                local.update {
                    it.copy(
                        pages = it.pages + (index to bytes),
                        pageStates = it.pageStates - index,
                        pageSizes = if (size == null) it.pageSizes else it.pageSizes + (index to size),
                    )
                }
            }
            pageJobs[index] = job
        }
    }

    /** 临近章节末尾时预取下一章前 2 页（失败静默，不影响主链路）。 */
    private fun prefetchNextChapter() {
        val next = machine.nextChapterId() ?: return
        if (prefetchingChapter == next) return
        val total = machine.currentPageCount()
        if (total <= 0 || machine.currentPageIndex() < total - 2) return
        prefetchingChapter = next
        worker.launch {
            try {
                val payload = chapters.payload(workId, next)
                val urls = (payload as? core.model.ChapterPayload.Images)?.urls.orEmpty().take(2)
                for (url in urls) {
                    val request = runCatching { chapters.resolvePage(workId, next, url) }
                        .getOrElse { ReaderPageRequest(url) }
                    val key = cacheKey(next, request)
                    if (pageCache.get(key) != null) continue
                    pagePermits.withPermit {
                        val bytes = pages(request, domains)
                        pageCache.put(key, bytes, bytes.size.toLong())
                    }
                }
            } catch (_: Exception) {
                // 预取失败不打扰读者，正式进入下一章时会重试。
            } finally {
                prefetchingChapter = null
            }
        }
    }

    private fun dropWindow() {
        windowVersion++
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        local.update { it.copy(pages = emptyMap(), pageStates = emptyMap(), pageSizes = emptyMap(), pdfBytes = null) }
    }

    private fun cacheKey(chapter: String, request: ReaderPageRequest): String =
        "$chapter:${request.effectiveUrl}"

    private suspend fun saveNow(workId: String, chapterId: String, page: Int, total: Int) {
        if (total <= 0 || chapterId.isEmpty()) return
        runCatching {
            saveProgress(
                ReadingProgress(
                    workId = workId,
                    chapterId = chapterId,
                    pageIndex = page,
                    percent = ((page + 1).toFloat() / total).coerceIn(0f, 1f),
                    updatedAtEpochMs = nowMs(),
                    synced = false,
                    charOffset = local.value.savedCharOffset,
                    layoutKey = local.value.layoutKey,
                ),
            )
        }
    }

    private fun toast(message: String) {
        viewModelScope.launch { _effect.send(ReaderContract.Effect.Toast(message)) }
    }

    private fun msChapter(ms: ReaderState): String = when (ms) {
        is ReaderState.ResolvingChapter -> ms.chapterId
        is ReaderState.Loading -> ms.chapterId
        else -> chapterId
    }
}

/** 分页字节用例（ViewModel 直达数据源是不允许的，见 data-layer 技能）。 */
class LoadPageBytesUseCase(
    private val images: ImageFetcher,
) {
    suspend operator fun invoke(url: String, domains: Set<String>): ByteArray =
        images.fetch(ImageRequest(url, domains)).bytes

    suspend operator fun invoke(request: ReaderPageRequest, domains: Set<String>): ByteArray =
        images.fetch(
            ImageRequest(
                url = request.effectiveUrl,
                domains = domains,
                headers = request.headers,
                config = ImageLoadingConfig(maxRetries = request.maxRetries),
            ),
        ).bytes
}
