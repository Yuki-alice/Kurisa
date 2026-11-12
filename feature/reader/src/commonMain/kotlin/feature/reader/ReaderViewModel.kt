package feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import core.common.Debouncer
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
) : ViewModel() {

    private data class Local(
        val mode: ReadingMode = ReadingMode.PAGED,
        val reachedEnd: Boolean = false,
        val pages: Map<Int, ByteArray> = emptyMap(),
        val pageSizes: Map<Int, core.reader.PageSize> = emptyMap(),
        val pageStates: Map<Int, ReaderContract.PageLoadState> = emptyMap(),
        val article: String? = null,
        val settings: ReadingConfig = ReadingConfig(),
        val menuVisible: Boolean = false,
        val chapterListVisible: Boolean = false,
        val settingsVisible: Boolean = false,
        /** 章节书签（内存态；持久化随 BookmarkRepository 落地）。 */
        val bookmarks: Set<String> = emptySet(),
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
        pageCount = pageCount,
        mode = l.mode,
        loading = loading,
        error = error,
        reachedEnd = reachedEnd,
        pages = l.pages,
        pageStates = l.pageStates,
        pageSizes = l.pageSizes,
        article = l.article,
        chapters = machine.chapters(),
        previousChapterTitle = machine.chapters().getOrNull(machine.chapterIndex() - 1)?.title,
        nextChapterTitle = machine.chapters().getOrNull(machine.chapterIndex() + 1)?.title,
        bookmarked = (machine.currentChapterId() ?: chapterId) in l.bookmarks,
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
                machine.pageTo(intent.index)
                if (local.value.menuVisible) {
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
                if (!machine.prevChapter()) {
                    _effect.send(ReaderContract.Effect.Toast("已是第一章"))
                }
            }
            is ReaderContract.Intent.RetryClicked -> viewModelScope.launch {
                machine.retry()
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
            is ReaderContract.Intent.ToggleBookmark -> {
                val id = machine.currentChapterId() ?: chapterId
                local.update {
                    it.copy(bookmarks = if (id in it.bookmarks) it.bookmarks - id else it.bookmarks + id)
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
                dropWindow()
                viewModelScope.launch { machine.jumpToChapter(intent.chapterId) }
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
        }
    }

    private fun launchNextChapter() {
        if (machine.state.value is ReaderState.Loading) return
        viewModelScope.launch {
            endNotifiedFor = null
            local.update { it.copy(reachedEnd = false) }
            dropWindow()
            machine.nextChapter()
        }
    }

    private suspend fun open() {
        machine.openChapter(workId, chapterId, chapters)
    }

    private suspend fun onReady(w: String, c: String, pageCount: Int) {
        val saved = runCatching { loadProgress(w) }.getOrNull()
        val savedPage = saved?.pageIndex
        var idx = 0
        if (saved != null && saved.chapterId == c && savedPage != null) {
            idx = savedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            machine.pageTo(idx)
        }
        local.update {
            it.copy(
                article = machine.currentText(),
                reachedEnd = false,
                pageStates = emptyMap(),
            )
        }
        ensureWindow()
        saveNow(w, c, idx, pageCount)
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
        local.update { it.copy(pages = emptyMap(), pageStates = emptyMap()) }
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

/** Koin 接入前手写 Factory（M5 改 viewModelOf；SavedStateHandle 按需再加）。 */
class ReaderViewModelFactory(
    private val workId: String,
    private val chapterId: String,
    private val chapters: ChapterProvider,
    private val saveProgress: SaveReadingProgressUseCase,
    private val loadProgress: LoadReadingProgressUseCase,
    private val pages: LoadPageBytesUseCase,
    private val domains: Set<String>,
    private val machine: ReaderStateMachine = ReaderStateMachine(),
    private val settingsStore: ReaderSettingsStore = DefaultReaderSettingsStore,
    private val mangaTitle: String = "",
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: kotlin.reflect.KClass<T>,
        extras: androidx.lifecycle.viewmodel.CreationExtras,
    ): T {
        require(modelClass == ReaderViewModel::class) { "仅支持 ReaderViewModel" }
        return ReaderViewModel(
            workId, chapterId, machine, chapters,
            saveProgress, loadProgress, pages, domains,
            settingsStore = settingsStore,
            mangaTitle = mangaTitle,
        ) as T
    }
}
