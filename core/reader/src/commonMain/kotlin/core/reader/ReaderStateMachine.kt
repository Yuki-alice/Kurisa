package core.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 阅读器外层状态（架构 6.1；漫画与小说共用骨架，差异在 payload）。 */
sealed interface ReaderState {
    data object Idle : ReaderState
    data class ResolvingChapter(val workId: String, val chapterId: String) : ReaderState
    data class Loading(val workId: String, val chapterId: String) : ReaderState
    data class Ready(val workId: String, val chapterId: String, val pageCount: Int) : ReaderState
    data class Reading(val workId: String, val chapterId: String, val pageIndex: Int) : ReaderState
    data class Preloading(val workId: String, val chapterId: String, val pageIndex: Int) : ReaderState
    data class ChapterEnd(val workId: String, val chapterId: String) : ReaderState
    data class Error(val message: String, val retryable: Boolean = true) : ReaderState
}

/** 阅读方向（Paged 单页/双页、Continuous 长条、RTL 由 M3 状态机消费）。 */
enum class ReadingDirection { LTR, RTL, VERTICAL }

/**
 * 外层状态机骨架（M3 填入章节序列/预加载窗口/进度持久化/错误重试）。
 * 状态只能经本机推进；UI 与 ViewModel 只订阅 [state]。
 */
class ReaderStateMachine {
    private val _state = MutableStateFlow<ReaderState>(ReaderState.Idle)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    fun resolve(workId: String, chapterId: String) {
        _state.value = ReaderState.ResolvingChapter(workId, chapterId)
    }

    fun loading(workId: String, chapterId: String) {
        _state.value = ReaderState.Loading(workId, chapterId)
    }

    fun ready(workId: String, chapterId: String, pageCount: Int) {
        require(pageCount > 0)
        _state.value = ReaderState.Ready(workId, chapterId, pageCount)
    }

    fun pageTo(index: Int) {
        val cur = _state.value
        val (workId, chapterId) = when (cur) {
            is ReaderState.Ready -> cur.workId to cur.chapterId
            is ReaderState.Reading -> cur.workId to cur.chapterId
            is ReaderState.Preloading -> cur.workId to cur.chapterId
            else -> return
        }
        _state.value = ReaderState.Reading(workId, chapterId, index)
    }

    fun fail(message: String, retryable: Boolean = true) {
        _state.value = ReaderState.Error(message, retryable)
    }

    fun reset() {
        _state.value = ReaderState.Idle
    }
}
