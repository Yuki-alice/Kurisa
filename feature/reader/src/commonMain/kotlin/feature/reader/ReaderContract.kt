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
        /** 当前章节书签（持久化随 BookmarkRepository 落地，重开恢复点亮）。 */
        val bookmarked: Boolean = false,
        /** 本作品书签列表（打开书签面板时加载）。 */
        val bookmarkList: List<core.model.Bookmark> = emptyList(),
        /** 书签面板可见性。 */
        val bookmarksVisible: Boolean = false,
        /** 正在编辑笔记的书签 id（null = 未打开编辑器）。 */
        val editingNoteId: String? = null,
        val editingHighlightNoteId: String? = null,
        /** 拖拽选区（文档坐标闭区间；预选中，未落盘）。 */
        val selectionRange: IntRange? = null,
        /** 同章内书签定位请求（Viewer 执行滚动后经 SeekHandled 回收；搜索跳转复用同一通道）。 */
        val bookmarkSeek: BookmarkSeek? = null,
        /** 划线模式（开时点段落即标/取消，不翻页）。 */
        val highlightMode: Boolean = false,
        /** 当前章节划线列表（打开时加载，供渲染与开关）。 */
        val highlightList: List<core.model.Highlight> = emptyList(),
        /** 全文搜索面板可见性。 */
        val searchVisible: Boolean = false,
        /** 全文搜索关键字。 */
        val searchQuery: String = "",
        /** 全文搜索命中（规范化全文字符区间），供高亮与计数。 */
        val searchMatches: List<IntRange> = emptyList(),
        /** 当前高亮命中的下标。 */
        val searchIndex: Int = 0,
        val pageIndex: Int = 0,
        val pageCount: Int = 0,
        val mode: ReadingMode = ReadingMode.PAGED,
        val loading: Boolean = true,
        val error: String? = null,
        val reachedEnd: Boolean = false,
        /** 当前预加载窗口的字节（key=页下标；窗外已驱逐）。 */
        val pages: Map<Int, ByteArray> = emptyMap(),
        /** 页图尺寸（header-only 解析；未知尺寸按非宽页处理）。 */
        val pageSizes: Map<Int, core.reader.PageSize> = emptyMap(),
        /** PDF 整档字节（专用阅读器用；非空时不走页管线）。 */
        val pdfBytes: ByteArray? = null,
        /** 页级加载/错误状态（Ready 不在其中，见 [pages]）。 */
        val pageStates: Map<Int, PageLoadState> = emptyMap(),
        /** 小说正文原文（兼容旧调用方；新版渲染走 [novel]）。 */
        val article: String? = null,
        /** 结构化正文（M4；漫画为 null）。 */
        val novel: core.model.NovelDocument? = null,
        /** 恢复阅读时使用的字符锚点（从进度读出，Viewer 首次排版后定位）。 */
        val novelAnchor: Int = 0,
        /** 小说文内图片业务头（key=重写后 effectiveUrl；仅 Referer 类，UA/Cookie 由宿主注入）。 */
        val novelImageHeaders: Map<String, Map<String, String>> = emptyMap(),
        /** 当前分页结果的排版键，随进度一起落盘。 */
        val novelLayoutKey: String = "",
        /** 当前设置作用域（`global` 或 `book:<workId>`；设置面板据此展示按书状态）。 */
        val settingsScope: String = "global",
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
        data object ToggleCropBorders : Intent
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
        data object OpenBookmarks : Intent
        data object CloseBookmarks : Intent
        data class StartEditNote(val id: String) : Intent
        data class SaveNote(val note: String) : Intent
        data object CancelEditNote : Intent
        data class JumpToBookmark(val id: String) : Intent
        data class BookmarkSeekHandled(val nonce: Long) : Intent
        data class JumpToHighlight(val id: String) : Intent
        data class RemoveHighlight(val id: String) : Intent
        data class StartEditHighlightNote(val id: String) : Intent
        data class SaveHighlightNote(val note: String) : Intent
        data object CancelEditHighlightNote : Intent
        data class UpdateNovelSelection(val start: Int, val end: Int) : Intent
        data object CommitNovelSelection : Intent
        data object ClearNovelSelection : Intent
        data object ToggleHighlightMode : Intent
        data class ToggleBlockHighlight(val charStart: Int, val charEnd: Int) : Intent
        data object OpenSearch : Intent
        data object CloseSearch : Intent
        data class SearchQueryChanged(val query: String) : Intent
        data object SearchNext : Intent
        data object SearchPrev : Intent
        /** 将当前全部命中转为划线（精确区间，无需手势选择）。 */
        data object HighlightAllSearchMatches : Intent
        /** 小说分页完成：回传虚拟页数与排版键，供状态机/进度使用。 */
        data class NovelPagesReady(val pageCount: Int, val layoutKey: String) : Intent
        /** 小说当前位置的字符锚点（重排后恢复用）。 */
        data class NovelAnchorChanged(val charOffset: Int, val layoutKey: String) : Intent
        data class NovelFontSizeChanged(val sp: Float) : Intent
        data class NovelLineHeightChanged(val multiple: Float) : Intent
        data class NovelParagraphSpacingChanged(val dp: Float) : Intent
        data class NovelPageMarginChanged(val dp: Float) : Intent
        data class NovelParagraphIndentChanged(val em: Float) : Intent
        /** 清除本书覆写并回退全局设置。 */
        data object ResetBookSettings : Intent
    }

    /** 同章书签定位请求（charOffset 小说字符锚点；nonce 防重放）。 */
    data class BookmarkSeek(val charOffset: Int, val nonce: Long)

    /** 一次性事件（Channel 精确一次投递，禁止 SharedFlow replay）。 */
    sealed interface Effect {
        data class Toast(val message: String) : Effect
    }
}
