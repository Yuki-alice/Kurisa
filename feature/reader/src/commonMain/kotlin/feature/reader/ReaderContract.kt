package feature.reader

import androidx.compose.runtime.Immutable
import core.reader.ReadingMode

/**
 * 阅读器屏契约（MVI：单屏单 State，意图 past-tense 命名，无回调/lambda）。
 * 导航（返回）走 Screen lambda（见 ADR-008），不进 Intent。
 */
object ReaderContract {

    // @Immutable 有效性说明：pages 的 ByteArray 发布后永不原地修改
    // （窗口更新一律换新 Map + 新数组），引用变化即内容变化，可跳过重组安全。
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
        /** 当前预加载窗口的字节（key=页下标；窗外已驱逐）。 */
        val pages: Map<Int, ByteArray> = emptyMap(),
        /** 小说正文（漫画为 null；M4 做排版分页）。 */
        val article: String? = null,
    )

    sealed interface Intent {
        data class PageChanged(val index: Int) : Intent
        data object NextPage : Intent
        data object PrevPage : Intent
        data object NextChapter : Intent
        data object PrevChapter : Intent
        data object RetryClicked : Intent
        /** 当前窗口重抓（图片失败占位上的重试入口）。 */
        data object RefreshPage : Intent
        data class ModeChanged(val mode: ReadingMode) : Intent
    }

    /** 一次性事件（Channel 精确一次投递，禁止 SharedFlow replay）。 */
    sealed interface Effect {
        data class Toast(val message: String) : Effect
    }
}
