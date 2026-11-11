package feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import core.common.Debouncer
import core.model.ReadingProgress
import core.reader.ChapterProvider
import core.reader.ReaderState
import core.reader.ReaderStateMachine
import core.reader.ReadingMode
import kotlinx.coroutines.CoroutineScope
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
 * 防竞态纪律（M3 Slice A 实录）：
 * - 页码/总数一律读机器同步快照（currentPageIndex/currentPageCount），
 *   永不读屏 State 推导（StateFlow 合流会压缩 Ready，导致总数卡 0）。
 * - 保存走显式参数 saveNow(w, c, page, total)，不读 state.value。
 * - 打开/重试/切章委托状态机；Ready 后恢复进度（load→pageTo→save，防丢）。
 * - 章节末端 Toast 走 Effect；异常收敛为 Error 状态；持久化失败不中断阅读。
 */
class ReaderViewModel(
    private val workId: String,
    private val chapterId: String,
    private val machine: ReaderStateMachine = ReaderStateMachine(),
    private val chapters: ChapterProvider,
    private val saveProgress: SaveReadingProgressUseCase,
    private val loadProgress: LoadReadingProgressUseCase,
    /**
     * 防抖作用域（默认 viewModelScope；测试注入 TestScope）。
     * 虚拟时间纪律：本工程 advanceTimeBy 恰到期不触发，必须推过期限
     * （见单测 499/+5 写法；M3 Slice A 实录）。
     */
    saveScope: CoroutineScope? = null,
) : ViewModel() {

    private data class Local(val mode: ReadingMode, val reachedEnd: Boolean)

    private val _effect = Channel<ReaderContract.Effect>(Channel.BUFFERED)
    val effect: Flow<ReaderContract.Effect> = _effect.receiveAsFlow()

    private val local = kotlinx.coroutines.flow.MutableStateFlow(
        Local(ReadingMode.PAGED, false)
    )

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
                    )
                is ReaderState.Preloading ->
                    ReaderContract.State(
                        workId = workId, chapterId = ms.chapterId,
                        pageIndex = ms.pageIndex,
                        pageCount = machine.currentPageCount(),
                        mode = l.mode, loading = false,
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

    private val saver = Debouncer(saveScope ?: viewModelScope, 500) {
        val w = workId
        val c = machine.currentChapterId() ?: return@Debouncer
        saveNow(w, c, machine.currentPageIndex(), machine.currentPageCount())
    }
    private var endNotifiedFor: String? = null

    init {
        viewModelScope.launch {
            machine.state.collect { ms ->
                if (ms is ReaderState.Ready) onReady(ms.workId, ms.chapterId, ms.pageCount)
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
                scheduleSave()
            }
            is ReaderContract.Intent.NextPage -> {
                val next = machine.currentPageIndex() + 1
                val total = machine.currentPageCount()
                if (next < total) {
                    machine.pageTo(next)
                    scheduleSave()
                } else {
                    toast("已是本章最后一页")
                }
            }
            is ReaderContract.Intent.PrevPage -> {
                val prev = machine.currentPageIndex() - 1
                if (prev >= 0) {
                    machine.pageTo(prev)
                    scheduleSave()
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
        saveNow(w, c, idx, pageCount)
    }

    private fun scheduleSave() {
        saver.submit()
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

/** Koin 接入前手写 Factory（M5 改 viewModelOf；SavedStateHandle 按需再加）。 */
class ReaderViewModelFactory(
    private val workId: String,
    private val chapterId: String,
    private val chapters: ChapterProvider,
    private val saveProgress: SaveReadingProgressUseCase,
    private val loadProgress: LoadReadingProgressUseCase,
    private val machine: ReaderStateMachine = ReaderStateMachine(),
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: kotlin.reflect.KClass<T>,
        extras: androidx.lifecycle.viewmodel.CreationExtras,
    ): T {
        require(modelClass == ReaderViewModel::class) { "仅支持 ReaderViewModel" }
        return ReaderViewModel(workId, chapterId, machine, chapters, saveProgress, loadProgress) as T
    }
}
