package feature.reader

import androidx.compose.runtime.Immutable
import core.reader.ChapterRef
import core.reader.ReadingConfig
import core.reader.ReadingMode

/**
 * 阅读器屏契约（MVI：单屏单 State，意图 past-tense 命名，无回调/lambda）。
 * 导航（返回）走 Screen lambda（见 ADR-008），不进 Intent。
 */
object ReaderContract {

    /** 页级加载状态；Ready 由 [State.pages] 中的字节表达。 */
    sealed interface PageLoadState {
        data object Loading : PageLoadState
        data class Error(val message: String) : PageLoadState
    }

    // @Immutable 有效性说明：pages 的 ByteArray 发布后永不原地修改
    // （窗口更新一律换新 Map + 新数组），引用变化即内容变化，可跳过重组安全。
    @Immutable
    data class State(
        val workId: String = "",
        val chapterId: String = "",
        /** 作品名（顶栏主标题；调用方传入，未知时为空）。 */
        val mangaTitle: String = "",
        val chapterTitle: String = "",
        val chapterIndex: Int = 0,
        val chapterCount: Int = 0,
        /** 相邻章节标题（过渡卡用；无则回退默认文案）。 */
        val previousChapterTitle: String? = null,
        val nextChapterTitle: String? = null,
        /** 当前章节书签（内存态；持久化随 BookmarkRepository 落地）。 */
        val bookmarked: Boolean = false,
        val pageIndex: Int = 0,
        val pageCount: Int = 0,
        val mode: ReadingMode = ReadingMode.PAGED,
        val loading: Boolean = true,
        val error: String? = null,
        val reachedEnd: Boolean = false,
        /** 当前预加载窗口的字节（key=页下标；窗外已驱逐）。 */
        val pages: Map<Int, ByteArray> = emptyMap(),
        /** 页级加载/错误状态（Ready 不在其中，见 [pages]）。 */
        val pageStates: Map<Int, PageLoadState> = emptyMap(),
        /** 小说正文（漫画为 null；M4 做排版分页）。 */
        val article: String? = null,
        val chapters: List<ChapterRef> = emptyList(),
        val settings: ReadingConfig = ReadingConfig(),
        val menuVisible: Boolean = false,
        val chapterListVisible: Boolean = false,
        val settingsVisible: Boolean = false,
        val hasPreviousChapter: Boolean = false,
        val hasNextChapter: Boolean = false,
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
        data class DirectionChanged(val direction: core.reader.ReadingDirection) : Intent
        data class ScaleChanged(val scaleType: core.reader.ImageScaleType) : Intent
        data class TapZoneChanged(val mode: core.reader.TapZoneMode) : Intent
        data class TapInvertChanged(val mode: core.reader.TapInvertMode) : Intent
        data object ToggleSmallerTapZone : Intent
        data object ToggleInvertDoublePages : Intent
        data object ToggleVolumeKeys : Intent
        data object ToggleVolumeKeysInverted : Intent
        data object ToggleReadWithLongTap : Intent
        data object ToggleBookmark : Intent
        data object ToggleInvertedColors : Intent
        data object ToggleAutoScroll : Intent
        data class AutoScrollIntervalChanged(val seconds: Float) : Intent
        data class TintColorChanged(val argb: Long?) : Intent
        data object ToggleFlashOnChange : Intent
        data class FlashDurationChanged(val millis: Int) : Intent
        data object ToggleFlashWhite : Intent
        data class ThemeChanged(val theme: core.reader.ReaderTheme) : Intent
        data object ToggleDoublePage : Intent
        data object ToggleGrayscale : Intent
        data object TogglePageNumber : Intent
        data class WebtoonPaddingChanged(val percent: Int) : Intent
        data class DimLevelChanged(val percent: Int) : Intent
        data object ToggleMenu : Intent
        data object CloseMenu : Intent
        data object OpenChapterList : Intent
        data object CloseChapterList : Intent
        data object OpenSettings : Intent
        data object CloseSettings : Intent
        data class SelectChapter(val chapterId: String) : Intent
        data class RetryPage(val index: Int) : Intent
    }

    /** 一次性事件（Channel 精确一次投递，禁止 SharedFlow replay）。 */
    sealed interface Effect {
        data class Toast(val message: String) : Effect
    }
}
