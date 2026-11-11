package feature.reader

import androidx.compose.runtime.Immutable
import core.reader.ReadingMode

/**
 * 阅读器屏契约（MVI：单屏单 State，意图 past-tense 命名，无回调/lambda）。
 * 导航（返回）走 Screen lambda（见 ADR-008），不进 Intent。
 */
object ReaderContract {

    @Immutable
    data class State(
        val workId: String = "",
        val chapterId: String = "",
        val chapterTitle: String = "",
        val chapterIndex: Int = 0,
        val chapterCount: Int = 0,
        val pageIndex: Int = 0,
        val pageCount: Int = 0,
        val mode: ReadingMode = ReadingMode.PAGED,
        val loading: Boolean = true,
        val error: String? = null,
        val reachedEnd: Boolean = false,
    )

    sealed interface Intent {
        data class PageChanged(val index: Int) : Intent
        data object NextPage : Intent
        data object PrevPage : Intent
        data object NextChapter : Intent
        data object PrevChapter : Intent
        data object RetryClicked : Intent
        data class ModeChanged(val mode: ReadingMode) : Intent
    }

    /** 一次性事件（Channel 精确一次投递，禁止 SharedFlow replay）。 */
    sealed interface Effect {
        data class Toast(val message: String) : Effect
    }
}
