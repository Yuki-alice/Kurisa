package core.reader

import core.model.ChapterPayload
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

/** 阅读模式（单页 Paged / 长条 Continuous；双页拼接与 RTL 由方向+配置表达）。 */
enum class ReadingMode { PAGED, CONTINUOUS }

/** 阅读方向（RTL 翻页方向、VERTICAL 长条滚动）。 */
enum class ReadingDirection { LTR, RTL, VERTICAL }

/** 图片缩放策略（对齐 Komikku 的 imageScaleType；UI 层映射为 ContentScale）。 */
enum class ImageScaleType { FIT, FIT_WIDTH, FIT_HEIGHT, ORIGINAL, SMART_FIT }

/** 阅读器画布主题（黑 / 深灰 / 白，对齐 Komikku readerTheme）。 */
enum class ReaderTheme { BLACK, DARK, WHITE }

/** 点按分区布局（对齐 Komikku 的导航模式）。 */
enum class TapZoneMode { LEFT_RIGHT, L_SHAPE, EDGE, KINDLISH, DISABLED }

/** 点按动作（纯逻辑，可由单测锁定）。 */
enum class TapAction { PREV, NEXT, MENU, CLOSE_MENU, NONE }

/** 点按反转（对齐 Komikku TappingInvertMode：对归一化坐标做镜像后再判定）。 */
enum class TapInvertMode { NONE, HORIZONTAL, VERTICAL, BOTH }

/**
 * 阅读配置。字段全部有默认值，旧调用方（M3 状态机测试）无需改动。
 */
data class ReadingConfig(
    val mode: ReadingMode = ReadingMode.PAGED,
    val direction: ReadingDirection = ReadingDirection.LTR,
    val scaleType: ImageScaleType = ImageScaleType.FIT,
    val tapZone: TapZoneMode = TapZoneMode.LEFT_RIGHT,
    /** 点按反转（NONE/HORIZONTAL/VERTICAL/BOTH，对齐 Komikku）。 */
    val tapInvert: TapInvertMode = TapInvertMode.NONE,
    /** 缩小点按热区（0.33 -> 0.25，对齐 Komikku smallerTapZone）。 */
    val smallerTapZone: Boolean = false,
    /** 双页顺序取反（`LTR xor invert` 定序，对齐 Komikku invertDoublePages）。 */
    val invertDoublePages: Boolean = false,
    val doublePage: Boolean = false,
    val showPageNumber: Boolean = true,
    val webtoonSidePaddingPercent: Int = 0,
    val theme: ReaderTheme = ReaderTheme.BLACK,
    val grayscale: Boolean = false,
    /** 亮度遮罩（0~60%，只压暗，不改系统亮度；对齐 Komikku customBrightness 的负值区间）。 */
    val dimLevel: Int = 0,
    val chapterTransition: Boolean = true,
    /** 音量键翻页（对齐 Komikku reader_volume_keys，默认开；菜单打开时恒关闭菜单）。 */
    val volumeKeysEnabled: Boolean = true,
    /** 音量键反转（对齐 Komikku reader_volume_keys_inverted）。 */
    val volumeKeysInverted: Boolean = false,
    /** 长按唤起菜单（对齐 Komikku readWithLongTap，默认开）。 */
    val readWithLongTap: Boolean = true,
    /** 反色（对齐 Komikku invertedColors，与灰度可叠加）。 */
    val invertedColors: Boolean = false,
    /** 自动滚动（对齐 Komikku autoscroll；条漫 UI 侧循环，分页 ViewModel 侧推进）。 */
    val autoScroll: Boolean = false,
    /** 自动滚动间隔秒（对齐 Komikku autoscrollInterval，1~10s）。 */
    val autoScrollIntervalSec: Float = 3f,
    /** 颜色滤镜 ARGB（null=关；对齐 Komikku colorFilter，暂只 SrcOver）。 */
    val tintColorArgb: Long? = null,
    /** 翻页闪屏（对齐 Komikku flashOnPageChange）。 */
    val flashOnPageChange: Boolean = false,
    /** 闪屏时长毫秒（对齐 Komikku flashDuration，100~1500ms）。 */
    val flashDurationMs: Int = 100,
    /** 闪屏白色（false=黑；对齐 Komikku flashColor BLACK/WHITE）。 */
    val flashWhite: Boolean = false,
    /** 自动裁边（对齐 Komikku cropBorders；解码层按需裁剪并写回缓存）。 */
    val cropBorders: Boolean = false,
    /** 小说排版（漫画字段不复用；变化触发 LayoutKey 失效与局部重排）。 */
    val typography: NovelTypography = NovelTypography(),
)

/**
 * 页请求（源图片经 `onImageLoad` 解析后的最终请求）。
 * `rewriteUrl` 为源重写后的地址；`headers` 已由契约层剥离 UA/Cookie 等危险头。
 */
data class ReaderPageRequest(
    val url: String,
    val rewriteUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxRetries: Int = 1,
) {
    val effectiveUrl: String get() = rewriteUrl?.takeIf { it.isNotBlank() } ?: url
}

/** 章节元数据（bridge 实现时由源 chapters 映射；M3 以 fake 驱动）。 */
data class ChapterRef(
    val id: String,
    val title: String,
    val ordinal: Int,
    /** 已读（目录未读点用；provider 未接进度时恒 false）。 */
    val isRead: Boolean = false,
)

/**
 * 章节解析（架构 6.1 的 ChapterResolver 角色）。
 * 实现（源 bridge）负责调插件取 payload；状态机只负责序列与状态。
 */
interface ChapterProvider {
    suspend fun chapters(workId: String): List<ChapterRef>
    suspend fun payload(workId: String, chapterId: String): ChapterPayload

    /**
     * 图片页请求解析（`comic.onImageLoad` 接线点）。
     * 默认直通 URL；`BridgeChapterProvider` 覆盖为按源配置重写 URL / 附加头 / 重试次数。
     * 每次取页时调用，避免在章节加载阶段对几十页逐一执行 JS。
     */
    suspend fun resolvePage(workId: String, chapterId: String, url: String): ReaderPageRequest =
        ReaderPageRequest(url)

    /**
     * 小说文内图片解析（`novel.onImageResolve` 接线点）。
     * 默认直通 URL；`BridgeChapterProvider` 覆盖为按源配置重写。
     * 与 [resolvePage] 不同：小说图片在正文解析后批量重写（ViewModel 侧），
     * 不在每次取页时逐一执行 JS，避免超长章对几十张图重复进桥。
     */
    suspend fun resolveNovelImage(url: String): ReaderPageRequest = ReaderPageRequest(url)

    /**
     * 源内直读字节（PDF 等专用阅读器章节用；默认不支持）。
     * @param chapterId 章节定位（本地源为 `"$manga/$entry"` 路径）。
     */
    suspend fun rawBytes(workId: String, chapterId: String): ByteArray =
        throw UnsupportedOperationException("该源不支持直读：$chapterId")
}

/**
 * payload → 可渲染页数。小说虚拟页数由 UI 排版完成后通过 [novelPages] 覆盖（M4）；
 * 不传时仍按 1 页，保证漫画路径与旧调用方语义不变。
 */
fun pageCountOf(payload: ChapterPayload, novelPages: Int? = null): Int = when (payload) {
    is ChapterPayload.Images -> payload.urls.size.coerceAtLeast(1)
    is ChapterPayload.Text -> novelPages?.coerceAtLeast(1) ?: 1
    // BE-C4：单资源媒体按 1 页计；播放器表现层随 M5 落地，后端类型化先到位。
    is ChapterPayload.Video -> 1
    is ChapterPayload.Audio -> 1
}

/**
 * 外层状态机（纯 Kotlin + StateFlow，无 UI 依赖）。
 * 状态只能经本机推进；UI 与 ViewModel 只订阅 [state]。
 * 章节解析与渲染解耦：provider 取 payload，PageSource（M3 Slice B）再变可渲染单元。
 */
class ReaderStateMachine {
    private val _state = MutableStateFlow<ReaderState>(ReaderState.Idle)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    var config: ReadingConfig = ReadingConfig()
        private set

    private var provider: ChapterProvider? = null
    private var workId: String? = null
    private var chapterList: List<ChapterRef> = emptyList()
    private var chapterIndex: Int = -1
    private var requestedChapterId: String? = null
    private var lastPayload: ChapterPayload? = null
    /** 小说虚拟页数（UI 排版完成后回传；漫画恒为 null）。 */
    private var novelPageCount: Int? = null

    /** 小说虚拟页数回填：`pageTo` / 预加载窗口 / 页数显示统一走有效页数。 */
    fun setNovelPageCount(count: Int) {
        novelPageCount = count.coerceAtLeast(1)
    }

    /** 当前小说载荷（含 format，供 ViewModel 结构化解析）。 */
    fun currentNovelPayload(): ChapterPayload.Text? = lastPayload as? ChapterPayload.Text

    fun setConfig(config: ReadingConfig) {
        this.config = config
    }

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
        val (w, c, total) = when (cur) {
            is ReaderState.Ready -> Triple(cur.workId, cur.chapterId, cur.pageCount)
            is ReaderState.Reading -> Triple(cur.workId, cur.chapterId, pageCountOf(lastPayload ?: return, novelPageCount))
            is ReaderState.Preloading -> Triple(cur.workId, cur.chapterId, pageCountOf(lastPayload ?: return, novelPageCount))
            else -> return
        }
        _state.value = ReaderState.Reading(w, c, index.coerceIn(0, (total - 1).coerceAtLeast(0)))
    }

    fun fail(message: String, retryable: Boolean = true) {
        _state.value = ReaderState.Error(message, retryable)
    }

    fun reset() {
        provider = null
        workId = null
        chapterList = emptyList()
        chapterIndex = -1
        lastPayload = null
        novelPageCount = null
        _state.value = ReaderState.Idle
    }

    /**
     * 打开章节（完整管线）：Resolving → Loading → Ready / Error。
     * 进度恢复由调用方（ViewModel 经 ProgressRepository + 防抖）完成，本机只到 Ready。
     */
    suspend fun openChapter(workId: String, chapterId: String, provider: ChapterProvider) {
        this.provider = provider
        this.workId = workId
        this.requestedChapterId = chapterId
        resolve(workId, chapterId)
        val chapters = runCatching { provider.chapters(workId) }.getOrElse {
            fail("目录加载失败：${it.message}", true)
            return
        }
        chapterList = chapters.sortedBy { it.ordinal }
        val idx = chapterList.indexOfFirst { it.id == chapterId }
        if (idx < 0) {
            fail("章节不存在：$chapterId", false)
            return
        }
        chapterIndex = idx
        loadCurrent()
    }

    /**
     * 仅装载目录并定位（不拉 payload；PDF 等专用阅读器章节用）。
     * 成功时进入 Ready（pageCount 占位 1，调用方不走页管线）；失败走 Error。
     */
    suspend fun openChapterListOnly(workId: String, chapterId: String, provider: ChapterProvider): Boolean {
        this.provider = provider
        this.workId = workId
        this.requestedChapterId = chapterId
        resolve(workId, chapterId)
        val chapters = runCatching { provider.chapters(workId) }.getOrElse {
            fail("目录加载失败：${it.message}", true)
            return false
        }
        chapterList = chapters.sortedBy { it.ordinal }
        val idx = chapterList.indexOfFirst { it.id == chapterId }
        if (idx < 0) {
            fail("章节不存在：$chapterId", false)
            return false
        }
        chapterIndex = idx
        lastPayload = null
        ready(workId, chapterId, 1)
        return true
    }

    /**
     * 重试当前章节（Error → Loading）。
     * 目录加载失败时 chapterList 为空，必须重走 openChapter，否则重试是 no-op。
     */
    suspend fun retry() {
        val w = workId ?: return
        val p = provider ?: return
        val c = currentChapterId() ?: requestedChapterId ?: return
        if (chapterList.isEmpty()) {
            openChapter(w, c, p)
            return
        }
        loadCurrentInternal(w, c)
    }

    /** 下一章；末章则进入 ChapterEnd（调用方据此 Toast/跳转）。 */
    suspend fun nextChapter() {
        val w = workId ?: return
        if (chapterIndex + 1 >= chapterList.size) {
            val c = currentChapterId() ?: return
            _state.value = ReaderState.ChapterEnd(w, c)
            return
        }
        chapterIndex++
        loadCurrentInternal(w, chapterList[chapterIndex].id)
    }

    /** 上一章；首章则保持不动（返回 false）。 */
    suspend fun prevChapter(): Boolean {
        val w = workId ?: return false
        if (chapterIndex - 1 < 0) return false
        chapterIndex--
        loadCurrentInternal(w, chapterList[chapterIndex].id)
        return true
    }

    fun currentChapterId(): String? =
        chapterList.getOrNull(chapterIndex)?.id

    fun currentChapter(): ChapterRef? =
        chapterList.getOrNull(chapterIndex)

    /** 章节目录快照（UI 目录/前后章判断用，不触发网络）。 */
    fun chapters(): List<ChapterRef> = chapterList

    /** 当前章节下标（无章节时 -1，供 UI 角标与进度定位）。 */
    fun chapterIndex(): Int = chapterIndex

    fun hasPreviousChapter(): Boolean = chapterIndex > 0

    fun hasNextChapter(): Boolean = chapterIndex >= 0 && chapterIndex + 1 < chapterList.size

    fun nextChapterId(): String? = chapterList.getOrNull(chapterIndex + 1)?.id

    fun previousChapterId(): String? = chapterList.getOrNull(chapterIndex - 1)?.id

    /** 目录跳转（复用已加载目录，不重新拉章节列表）。 */
    suspend fun jumpToChapter(chapterId: String) {
        val w = workId ?: return
        val idx = chapterList.indexOfFirst { it.id == chapterId }
        if (idx < 0) {
            fail("章节不存在：$chapterId", false)
            return
        }
        chapterIndex = idx
        loadCurrentInternal(w, chapterId)
    }

    /** 当前页下标（machine.state 的同步快照；ViewModel 防抖保存用，不读屏 State）。 */
    fun currentPageIndex(): Int = when (val cur = _state.value) {
        is ReaderState.Reading -> cur.pageIndex
        is ReaderState.Preloading -> cur.pageIndex
        else -> 0
    }

    /** 当前章节总页数（无载荷时 0）。 */
    fun currentPageCount(): Int = lastPayload?.let { pageCountOf(it, novelPageCount) } ?: 0

    /** 当前页图片 URL（漫画 Images；小说/未知为空，UI 据此分支）。 */
    fun currentPageUrls(): List<String> =
        (lastPayload as? ChapterPayload.Images)?.urls.orEmpty()

    /** 当前小说正文（Text 载荷；漫画为 null，M4 做排版分页）。 */
    fun currentText(): String? =
        (lastPayload as? ChapterPayload.Text)?.body

    fun chapterCount(): Int = chapterList.size

    /**
     * 预加载窗口（纯函数）：当前页前 [before] / 后 [after]（默认前 1 后 2，架构 6.2）。
     * 调用方（图片管线）据此预取；窗口随滑动动态调整。
     */
    fun preloadWindow(before: Int = 1, after: Int = 2): List<Int> {
        val (page, total) = when (val cur = _state.value) {
            is ReaderState.Reading -> cur.pageIndex to pageCountOf(lastPayload ?: return emptyList(), novelPageCount)
            is ReaderState.Ready -> 0 to cur.pageCount
            is ReaderState.Preloading -> cur.pageIndex to pageCountOf(lastPayload ?: return emptyList(), novelPageCount)
            else -> return emptyList()
        }
        if (total <= 0) return emptyList()
        val lo = (page - before).coerceAtLeast(0)
        val hi = (page + after).coerceAtMost(total - 1)
        if (hi < lo) return emptyList()
        return (lo..hi).toList()
    }

    private suspend fun loadCurrent() {
        val w = workId ?: return
        val c = currentChapterId() ?: return
        loadCurrentInternal(w, c)
    }

    private suspend fun loadCurrentInternal(w: String, c: String) {
        val p = provider ?: run {
            fail("provider 缺失", false)
            return
        }
        // 切章先清空虚拟页数，待 Viewer 排版完成后重新回填，避免沿用上一章页数。
        novelPageCount = null
        loading(w, c)
        val payload = runCatching { p.payload(w, c) }.getOrElse {
            fail("章节加载失败：${it.message}", true)
            return
        }
        lastPayload = payload
        ready(w, c, pageCountOf(payload, novelPageCount))
    }
}
