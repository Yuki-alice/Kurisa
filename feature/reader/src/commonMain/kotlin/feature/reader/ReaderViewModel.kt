package feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import core.common.Debouncer
import core.model.ReadingProgress
import core.reader.ChapterProvider
import core.reader.ReaderState
import core.reader.ReaderStateMachine
import core.reader.ReadingMode
import data.images.ImageFetcher
import data.images.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 阅读器 ViewModel（MVI：StateFlow 状态 + Channel 一次性事件）。
 *
 * 学科纪律（M3 Slice A 实录）：
 * - 页码/总数一律读机器同步快照，不读屏 State 推导（合流压缩致总数卡 0）。
 * - 保存走显式参数；虚拟时间断言必须推过期限（499/+5）。
 * - 图片字节只保留当前预加载窗口（切章/翻页即驱逐，窗口外完成直接丢弃）。
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
    /**
     * 工作作用域（默认 viewModelScope；测试注入 TestScope 使 delay 可虚拟推进）。
     * 防抖与分页抓取共用此域。
     */
    workScope: CoroutineScope? = null,
) : ViewModel() {

    private data class Local(
        val mode: ReadingMode,
        val reachedEnd: Boolean,
        val pages: Map<Int, ByteArray> = emptyMap(),
        val article: String? = null,
    )

    private val _effect = Channel<ReaderContract.Effect>(Channel.BUFFERED)
    val effect: Flow<ReaderContract.Effect> = _effect.receiveAsFlow()

    private val local = kotlinx.coroutines.flow.MutableStateFlow(
        Local(ReadingMode.PAGED, false)
    )

    private val worker: CoroutineScope = workScope ?: viewModelScope

    val state: StateFlow<ReaderContract.State> =
        combine(machine.state, local) { ms, l ->
            when (ms) {
                is ReaderState.Idle ->
                    ReaderContract.State(workId = workId, loading = true)
                is ReaderState.ResolvingChapter, is ReaderState.Loading ->
                    ReaderContract.State(
                        workId = workId, chapterId = msChapter(ms),
                        mode = l.mode, loading = true,
                    )
                is ReaderState.Ready ->
                    ReaderContract.State(
                        workId = workId, chapterId = ms.chapterId,
                        chapterTitle = machine.currentChapter()?.title.orEmpty(),
                        chapterIndex = machine.chapterIndex().coerceAtLeast(0),
                        chapterCount = machine.chapterCount(),
                        pageIndex = 0, pageCount = ms.pageCount,
                        mode = l.mode, loading = false,
                        pages = l.pages, article = l.article,
                    )
                is ReaderState.Reading ->
                    ReaderContract.State(
                        workId = workId, chapterId = ms.chapterId,
                        chapterTitle = machine.currentChapter()?.title.orEmpty(),
                        chapterIndex = machine.chapterIndex().coerceAtLeast(0),
                        chapterCount = machine.chapterCount(),
                        pageIndex = ms.pageIndex,
                        pageCount = machine.currentPageCount(),
                        mode = l.mode, loading = false, reachedEnd = l.reachedEnd,
                        pages = l.pages, article = l.article,
                    )
                is ReaderState.Preloading ->
                    ReaderContract.State(
                        workId = workId, chapterId = ms.chapterId,
                        pageIndex = ms.pageIndex,
                        pageCount = machine.currentPageCount(),
                        mode = l.mode, loading = false,
                        pages = l.pages, article = l.article,
                    )
                is ReaderState.ChapterEnd ->
                    ReaderContract.State(
                        workId = workId, chapterId = ms.chapterId,
                        mode = l.mode, loading = false, reachedEnd = true,
                    )
                is ReaderState.Error ->
                    ReaderContract.State(
                        workId = workId, chapterId = chapterId,
                        mode = l.mode, loading = false, error = ms.message,
                    )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderContract.State(workId = workId))

    private val saver = Debouncer(worker, 500) {
        val w = workId
        val c = machine.currentChapterId() ?: return@Debouncer
        saveNow(w, c, machine.currentPageIndex(), machine.currentPageCount())
    }
    private var endNotifiedFor: String? = null
    private var windowVersion = 0
    private var windowChapter: String? = null
    private val pageJobs = mutableMapOf<Int, Job>()

    init {
        viewModelScope.launch {
            machine.state.collect { ms ->
                if (ms is ReaderState.Ready) onReady(ms.workId, ms.chapterId, ms.pageCount)
                if (ms is ReaderState.Reading) ensureWindow()
                if (ms is ReaderState.ChapterEnd && endNotifiedFor != ms.chapterId) {
                    endNotifiedFor = ms.chapterId
                    local.value = local.value.copy(reachedEnd = true)
                    _effect.send(ReaderContract.Effect.Toast("已是最后一章"))
                }
            }
        }
        viewModelScope.launch { open() }
    }

    fun onIntent(intent: ReaderContract.Intent) {
        when (intent) {
            is ReaderContract.Intent.PageChanged -> {
                machine.pageTo(intent.index)
                ensureWindow()
                saver.submit()
            }
            is ReaderContract.Intent.NextPage -> {
                val next = machine.currentPageIndex() + 1
                val total = machine.currentPageCount()
                if (next < total) {
                    machine.pageTo(next)
                    ensureWindow()
                    saver.submit()
                } else {
                    toast("已是本章最后一页")
                }
            }
            is ReaderContract.Intent.PrevPage -> {
                val prev = machine.currentPageIndex() - 1
                if (prev >= 0) {
                    machine.pageTo(prev)
                    ensureWindow()
                    saver.submit()
                } else {
                    toast("已是本章第一页")
                }
            }
            is ReaderContract.Intent.NextChapter -> viewModelScope.launch {
                endNotifiedFor = null
                local.value = local.value.copy(reachedEnd = false)
                machine.nextChapter()
            }
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
                local.value = local.value.copy(mode = intent.mode)
                machine.setConfig(machine.config.copy(mode = intent.mode))
            }
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
        local.value = local.value.copy(article = machine.currentText())
        ensureWindow()
        saveNow(w, c, idx, pageCount)
    }

    /**
     * 窗口保障：补齐缺页抓取、取消窗外任务、驱逐窗外字节、丢弃过期完成。
     * 与 machine.preloadWindow() 同源（前 1 后 2），内存天然有界。
     * 任务与字节均按“当前章”作用域：切章时整窗作废（同下标跨章撞键是 M3 实录 bug）。
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
        // 取消窗外任务。
        val stale = pageJobs.keys.filterNot { it in window }
        stale.forEach { pageJobs.remove(it)?.cancel() }
        // 驱逐窗外字节。
        val kept = local.value.pages.filterKeys { it in window }
        if (kept.size != local.value.pages.size || local.value.article == null) {
            local.value = local.value.copy(pages = kept)
        }
        for (index in window) {
            if (index in local.value.pages || pageJobs.containsKey(index)) continue
            val url = urls.getOrNull(index) ?: continue
            val job = worker.launch {
                val bytes = runCatching { pages(url, domains) }.getOrNull() ?: return@launch
                if (v != windowVersion) return@launch // 过期完成直接丢弃
                pageJobs.remove(index)
                local.value = local.value.copy(pages = local.value.pages + (index to bytes))
            }
            pageJobs[index] = job
        }
    }

    private fun dropWindow() {
        windowVersion++
        pageJobs.values.forEach { it.cancel() }
        pageJobs.clear()
        local.value = local.value.copy(pages = emptyMap())
    }

    private suspend fun saveNow(workId: String, chapterId: String, page: Int, total: Int) {
        if (total <= 0 || chapterId.isEmpty()) return
        runCatching {
            saveProgress(
                ReadingProgress(
                    workId = workId,
                    chapterId = chapterId,
                    pageIndex = page,
                    percent = ((page + 1).toFloat() / total).coerceIn(0f, 1f),
                    updatedAtEpochMs = 0L,
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
        ) as T
    }
}
