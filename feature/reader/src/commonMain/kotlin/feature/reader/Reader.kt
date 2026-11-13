package feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import com.github.panpf.zoomimage.rememberCoilZoomState
import core.reader.ChapterProvider
import core.reader.ChapterRef
import core.reader.ImageScaleType
import core.reader.PageSpread
import core.reader.ReaderSettingsStore
import core.reader.ReaderTheme
import core.reader.ReadingConfig
import core.reader.ReadingDirection
import core.reader.ReadingMode
import core.reader.TapAction
import core.reader.TapInvertMode
import core.reader.TapZoneMode
import core.reader.buildSpreads
import core.reader.currentPageText
import core.reader.spreadIndexOfPage
import core.reader.tapActionAt
import data.images.ImageFetcher
import data.images.SourceImageFetcher
import data.localimport.LocalImageFetcher
import data.localimport.LocalSourceFileSystem
import dev.nucleusframework.pdfium.PdfReader
import dev.nucleusframework.pdfium.rememberPdfReaderState
import data.network.CookieStore
import data.network.SourceHttpClient
import data.network.defaultHttpClient
import data.repository.BookmarkRepository
import data.repository.NoopBookmarkRepository
import data.repository.ProgressRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 阅读器（漫画 & 小说共用外壳，内部分支；Viewer 由阅读模式选择）。 */
@Serializable
data class ReaderRoute(
    val workId: String,
    val chapterId: String,
    /** 作品归属源（B4：App 层据此绑定按作品路由；本地书为空串）。 */
    val sourceKey: String = "",
) : NavKey

@Composable
fun ReaderScreen(
    workId: String,
    chapterId: String,
    onBack: () -> Unit,
    chapters: ChapterProvider,
    domains: Set<String>,
    progress: ProgressRepository = NoopProgressRepository,
    settingsStore: ReaderSettingsStore = DefaultReaderSettingsStore,
    /** 本地库文件系统（非空时图片出口自动叠加本地抓取，`local://` 隐式 scheme）。 */
    localFs: LocalSourceFileSystem? = null,
    /** 作品名（顶栏主标题；未知时回退章节名）。 */
    mangaTitle: String = "",
    /** 书签持久化（默认内存态；平台壳传入 SQLDelight 实现）。 */
    bookmarks: BookmarkRepository = NoopBookmarkRepository,
    /**
     * 共享网络栈（B3）：平台壳把源装配用的同一个 [CookieStore] / [io.ktor.client.HttpClient]
     * 传进来，阅读器图片出口与源请求共享 Cookie 与配对 UA；不传时退化为独立实例，
     * 保证既有调用方与单测零改动。Koin 收口 VM 工厂仍属后续项。
     */
    cookies: CookieStore? = null,
    httpClient: io.ktor.client.HttpClient? = null,
) {
    val sharedCookies = remember(cookies) { cookies ?: CookieStore() }
    val sharedHttpClient = remember(httpClient) { httpClient ?: defaultHttpClient() }
    val images: ImageFetcher = remember(localFs, sharedCookies, sharedHttpClient) {
        val network = SourceImageFetcher(SourceHttpClient(sharedHttpClient, sharedCookies) { 0L })
        localFs?.let { LocalImageFetcher(it, network) } ?: network
    }
    val vm: ReaderViewModel = viewModel(
        key = "reader:$workId:$chapterId",
        factory = ReaderViewModelFactory(
            workId = workId,
            chapterId = chapterId,
            chapters = chapters,
            saveProgress = SaveReadingProgressUseCase(progress),
            loadProgress = LoadReadingProgressUseCase(progress),
            pages = LoadPageBytesUseCase(images),
            domains = domains,
            settingsStore = settingsStore,
            mangaTitle = mangaTitle,
            bookmarks = bookmarks,
        ),
    )
    ReaderScreenVm(
        workId = workId,
        onBack = onBack,
        vm = vm,
        images = images,
        domains = domains,
    )
}

/** VM 已就绪入口（预览/测试直接供 VM，不碰网络装配）。 */
@Composable
fun ReaderScreenVm(
    workId: String,
    onBack: () -> Unit,
    vm: ReaderViewModel,
    /** 小说文内图片出口（受控抓取器；为空时只显示占位符）。 */
    images: ImageFetcher? = null,
    domains: Set<String> = emptySet(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snacks = remember { SnackbarHostState() }
    // Effect 收集：LaunchedEffect(viewModel) 防重订阅（禁止 LaunchedEffect(state.*)）。
    LaunchedEffect(vm) {
        vm.effect.collect { effect ->
            when (effect) {
                is ReaderContract.Effect.Toast -> snacks.showSnackbar(effect.message)
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snacks) },
        // 阅读器自行处理安全区，内容铺满全屏（沉浸式前提）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        ReaderContent(
            state = state,
            onIntent = vm::onIntent,
            onBack = onBack,
            modifier = Modifier.padding(padding),
            images = images,
            domains = domains,
        )
    }
}

@Composable
fun ReaderContent(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 小说文内图片：沿用 ReaderScreen 装配的受控出口与域名白名单。 */
    images: ImageFetcher? = null,
    domains: Set<String> = emptySet(),
) {
    val settings = state.settings
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(settings.theme.canvasColor()),
    ) {
        // 双页页码语义（对齐 Komikku currentPageText；仅横屏双页时组 spread）。
        val landscape = maxWidth > maxHeight
        val pageLabel = remember(
            state.pageIndex, state.pageCount, settings.doublePage, landscape,
            settings.direction, settings.invertDoublePages,
        ) {
            if (settings.doublePage && landscape && state.pageCount > 1) {
                val spreads = buildSpreads(state.pageCount, doublePage = true)
                currentPageText(
                    spreads[spreadIndexOfPage(spreads, state.pageIndex)],
                    rtl = settings.direction == ReadingDirection.RTL,
                    invertDoublePages = settings.invertDoublePages,
                )
            } else {
                "${state.pageIndex + 1}"
            }
        }
        val pdfBytes = state.pdfBytes
    when {
            // 章节切换重建 Viewer：避免沿用上一章 PagerState/LazyListState 造成跳章串位。
            state.novel != null -> key(state.chapterId) {
                NovelReader(
                    state = state,
                    onIntent = onIntent,
                    images = images,
                    domains = domains,
                )
            }
            state.article != null -> PlainArticleContent(state.article, state.settings)
            pdfBytes != null -> PdfReaderView(pdfBytes, state, onIntent, onBack)
            state.loading && state.pageCount == 0 -> LoadingScreen()
            state.error != null && state.pageCount == 0 -> ErrorScreen(
                message = state.error,
                onRetry = { onIntent(ReaderContract.Intent.RetryClicked) },
                onBack = onBack,
            )
            // 章节切换时重建 Viewer：列表/分页状态以新章节的进度初始化，
            // 避免复用上一章 LazyListState/PagerState 造成“跳章后停在旧位置”。
            else -> key(state.chapterId) {
                when {
                    settings.mode == ReadingMode.CONTINUOUS -> WebtoonReader(state, onIntent)
                    settings.direction == ReadingDirection.VERTICAL ->
                        PagedReader(state, onIntent, vertical = true)
                    else -> PagedReader(state, onIntent, vertical = false)
                }
            }
        }

        // 亮度遮罩：只压暗内容，不拦截手势；overlay 在 chrome 之下。
        if (settings.dimLevel > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = settings.dimLevel / 100f)),
            )
        }
        // 颜色滤镜（对齐 Komikku colorFilter，暂只 SrcOver）。
        settings.tintColorArgb?.let { argb ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(argb)),
            )
        }
        // 翻页闪屏（对齐 Komikku flashOnPageChange）。
        if (settings.flashOnPageChange) {
            var flashing by remember(state.chapterId) { mutableStateOf(false) }
            LaunchedEffect(state.pageIndex, state.chapterId) {
                flashing = true
                kotlinx.coroutines.delay(settings.flashDurationMs.toLong())
                flashing = false
            }
            if (flashing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(if (settings.flashWhite) Color.White else Color.Black),
                )
            }
        }

        if (state.menuVisible) {
            ReaderChrome(state, onIntent, onBack, pageLabel)
        } else if (settings.showPageNumber && state.pageCount > 0 &&
            (state.article == null || state.novel != null)
        ) {
            PageIndicator(pageLabel, state.pageCount)
        }

        if (state.chapterListVisible) {
            ChapterListSheet(state, onIntent)
        }
        if (state.bookmarksVisible) {
            BookmarkListSheet(state, onIntent)
        }
        if (state.editingNoteId != null) {
            BookmarkNoteDialog(state, onIntent)
        }
        if (state.editingHighlightNoteId != null) {
            HighlightNoteDialog(state, onIntent)
        }
        if (state.searchVisible) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                ReaderSearchBar(state, onIntent)
            }
        }
        // 拖拽选区确认条（与搜索条同列避免遮挡；标出后落盘并清选区）。
        val selection = state.selectionRange
        if (selection != null && selection.last > selection.first) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onIntent(ReaderContract.Intent.CommitNovelSelection) }) {
                        Text("标出所选")
                    }
                    TextButton(onClick = { onIntent(ReaderContract.Intent.ClearNovelSelection) }) {
                        Text("取消")
                    }
                }
            }
        }
        if (state.settingsVisible) {
            ReaderSettingsSheet(state, onIntent)
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* PDF viewer（ComposePdfReader；pdfium 文档 API 为 internal，只走公开组件）                        */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun PdfReaderView(
    bytes: ByteArray,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    onBack: () -> Unit,
) {
    val pdfState = rememberPdfReaderState()
    LaunchedEffect(bytes) {
        pdfState.open(bytes)
    }
    DisposableEffect(Unit) {
        onDispose { pdfState.dispose() }
    }
    Box(Modifier.fillMaxSize()) {
        when {
            pdfState.error != null -> ErrorScreen(
                message = "PDF 打开失败：${pdfState.error}",
                onRetry = { onIntent(ReaderContract.Intent.RetryClicked) },
                onBack = onBack,
            )
            pdfState.isLoading -> LoadingScreen()
            else -> PdfReader(pdfState, Modifier.fillMaxSize())
        }
        // 复用同一套 Chrome：菜单/目录/设置与图片章节一致。
        if (state.menuVisible) {
            ReaderChrome(state, onIntent, onBack, "")
        }
        if (state.chapterListVisible) {
            ChapterListSheet(state, onIntent)
        }
        if (state.bookmarksVisible) {
            BookmarkListSheet(state, onIntent)
        }
        if (state.editingNoteId != null) {
            BookmarkNoteDialog(state, onIntent)
        }
        if (state.editingHighlightNoteId != null) {
            HighlightNoteDialog(state, onIntent)
        }
        if (state.settingsVisible) {
            ReaderSettingsSheet(state, onIntent)
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Paged viewer                                                                                    */
/* --------------------------------------------------------------------------------------------- */

private sealed interface PagerItem {
    data object PrevTransition : PagerItem
    data class Spread(val spread: PageSpread) : PagerItem
    data object NextTransition : PagerItem
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PagedReader(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    vertical: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionUp, Key.PageUp -> {
                        onIntent(ReaderContract.Intent.PrevPage)
                        true
                    }
                    Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar -> {
                        onIntent(ReaderContract.Intent.NextPage)
                        true
                    }
                    // 音量键：开关门控 + 反转（对齐 Komikku；菜单打开时由意图侧关闭菜单）。
                    Key.VolumeUp -> {
                        if (state.settings.volumeKeysEnabled) {
                            onIntent(
                                if (state.settings.volumeKeysInverted) ReaderContract.Intent.NextPage
                                else ReaderContract.Intent.PrevPage,
                            )
                            true
                        } else {
                            false
                        }
                    }
                    Key.VolumeDown -> {
                        if (state.settings.volumeKeysEnabled) {
                            onIntent(
                                if (state.settings.volumeKeysInverted) ReaderContract.Intent.PrevPage
                                else ReaderContract.Intent.NextPage,
                            )
                            true
                        } else {
                            false
                        }
                    }
                    Key.Menu -> {
                        onIntent(ReaderContract.Intent.ToggleMenu)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val landscape = maxWidth > maxHeight
        val doublePage = state.settings.doublePage && landscape
        // 宽页独占 spread（尺寸随页到达渐进精确；未知按非宽页配对）。
        val spreads = remember(state.pageCount, doublePage, state.pageSizes) {
            buildSpreads(state.pageCount, doublePage) { index ->
                state.pageSizes[index]?.isWide == true
            }
        }
        val showTransition = state.settings.chapterTransition
        val items = remember(spreads, state.hasPreviousChapter, state.hasNextChapter, showTransition) {
            buildList {
                if (showTransition && state.hasPreviousChapter) add(PagerItem.PrevTransition)
                spreads.forEach { add(PagerItem.Spread(it)) }
                if (showTransition && state.hasNextChapter) add(PagerItem.NextTransition)
            }
        }
        if (items.isEmpty()) {
            LoadingScreen()
            return@BoxWithConstraints
        }
        val prevOffset = if (items.firstOrNull() == PagerItem.PrevTransition) 1 else 0
        val target = (prevOffset + spreadIndexOfPage(spreads, state.pageIndex))
            .coerceIn(0, items.lastIndex)
        val pagerState = rememberPagerState(initialPage = target, pageCount = { items.size })

        // 进度恢复：Ready(0) → onReady(pageTo(saved)) 不会重建 Viewer，
        // 这里在章节内首次收到恢复页码后滚动一次；后续用户翻页时 currentPage 已同步，不再触发。
        LaunchedEffect(state.chapterId, state.pageIndex) {
            val desired = (prevOffset + spreadIndexOfPage(spreads, state.pageIndex))
                .coerceIn(0, items.lastIndex)
            if (!pagerState.isScrollInProgress && pagerState.currentPage != desired) {
                pagerState.scrollToPage(desired)
            }
        }

        LaunchedEffect(pagerState, items) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { page ->
                    when (val item = items.getOrNull(page)) {
                        is PagerItem.Spread -> onIntent(ReaderContract.Intent.PageChanged(item.spread.pages.first()))
                        PagerItem.PrevTransition -> onIntent(ReaderContract.Intent.PrevChapter)
                        PagerItem.NextTransition -> onIntent(ReaderContract.Intent.NextChapter)
                        null -> Unit
                    }
                }
        }

        if (vertical) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                PagerPage(items[page], state, onIntent)
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = state.settings.direction == ReadingDirection.RTL,
                beyondViewportPageCount = 1,
            ) { page ->
                PagerPage(items[page], state, onIntent)
            }
        }
    }
}

@Composable
private fun PagerPage(
    item: PagerItem,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    when (item) {
        PagerItem.PrevTransition -> TransitionCard(
            currentTitle = state.chapterTitle.takeIf { it.isNotEmpty() },
            targetTitle = state.previousChapterTitle,
            label = "上一章",
            onClick = { onIntent(ReaderContract.Intent.PrevChapter) },
            contentColor = state.settings.theme.onCanvasColor(),
        )
        PagerItem.NextTransition -> TransitionCard(
            currentTitle = state.chapterTitle.takeIf { it.isNotEmpty() },
            targetTitle = state.nextChapterTitle,
            label = "下一章",
            onClick = { onIntent(ReaderContract.Intent.NextChapter) },
            contentColor = state.settings.theme.onCanvasColor(),
        )
        is PagerItem.Spread -> Row(Modifier.fillMaxSize()) {
            // 显示顺序与页码文本一致：RTL xor invertDoublePages 时反转。
            val rtl = state.settings.direction == ReadingDirection.RTL
            val pages = if (rtl xor state.settings.invertDoublePages) {
                item.spread.pages.reversed()
            } else {
                item.spread.pages
            }
            pages.forEach { index ->
                PagedPageImage(
                    index = index,
                    state = state,
                    onIntent = onIntent,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun PagedPageImage(
    index: Int,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bytes = state.pages[index]
    val pageState = state.pageStates[index]
    val colorFilter = state.settings.imageColorFilter()
    if (bytes != null) {
        var size by remember { mutableStateOf(IntSize.Zero) }
        val zoomState = rememberCoilZoomState()
        CoilZoomAsyncImage(
            model = bytes,
            contentDescription = "第 ${index + 1} 页",
            modifier = modifier.onSizeChanged { size = it },
            contentScale = state.settings.scaleType.toContentScale(),
            colorFilter = colorFilter,
            zoomState = zoomState,
            scrollBar = null,
            onTap = { offset ->
                if (size.width > 0 && size.height > 0) {
                    handleTap(offset, size, state, onIntent)
                }
            },
            onLongPress = {
                if (state.settings.readWithLongTap || state.menuVisible) {
                    onIntent(ReaderContract.Intent.ToggleMenu)
                }
            },
        )
    } else if (pageState is ReaderContract.PageLoadState.Error) {
        PageErrorPlaceholder(
            message = pageState.message,
            onRetry = { onIntent(ReaderContract.Intent.RetryPage(index)) },
            modifier = modifier,
        )
    } else {
        PageLoadingPlaceholder(modifier)
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Webtoon viewer                                                                                  */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun WebtoonReader(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val prevOffset = if (state.settings.chapterTransition && state.hasPreviousChapter) 1 else 0
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = (prevOffset + state.pageIndex).coerceAtLeast(0),
        )
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        val screenHeightPx = with(density) { maxHeight.toPx() }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        var restored by remember(state.chapterId) { mutableStateOf(false) }
        if (state.settings.autoScroll && !state.menuVisible) {
            LaunchedEffect(state.settings.autoScrollIntervalSec, state.chapterId) {
                while (true) {
                    kotlinx.coroutines.delay((state.settings.autoScrollIntervalSec * 1000).toLong())
                    if (!listState.canScrollForward) break
                    listState.animateScrollBy(screenHeightPx * 0.75f)
                }
            }
        }

        // 只在首次恢复进度时定位；用户后续滚动不反向覆盖。
        LaunchedEffect(state.pageIndex) {
            val visiblePage = listState.firstVisibleItemIndex - prevOffset
            if (!restored && state.pageIndex > 0 && visiblePage == 0) {
                listState.scrollToItem((prevOffset + state.pageIndex).coerceAtLeast(0))
                restored = true
            }
        }

        LaunchedEffect(listState, prevOffset, state.chapterId, state.pageCount) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { index ->
                    val page = index - prevOffset
                    if (page in 0 until state.pageCount) {
                        onIntent(ReaderContract.Intent.PageChanged(page))
                    }
                }
        }

        var size by remember { mutableStateOf(IntSize.Zero) }
        val padding = maxWidth * (state.settings.webtoonSidePaddingPercent / 100f)
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp, Key.PageUp -> {
                            scope.launch { listState.animateScrollBy(-screenHeightPx * 0.75f) }
                            true
                        }
                        Key.DirectionDown, Key.PageDown, Key.Spacebar -> {
                            scope.launch { listState.animateScrollBy(screenHeightPx * 0.75f) }
                            true
                        }
                        Key.VolumeUp -> {
                            if (!state.settings.volumeKeysEnabled) {
                                false
                            } else {
                                val dir = if (state.settings.volumeKeysInverted) 1f else -1f
                                scope.launch { listState.animateScrollBy(dir * screenHeightPx * 0.75f) }
                                true
                            }
                        }
                        Key.VolumeDown -> {
                            if (!state.settings.volumeKeysEnabled) {
                                false
                            } else {
                                val dir = if (state.settings.volumeKeysInverted) -1f else 1f
                                scope.launch { listState.animateScrollBy(dir * screenHeightPx * 0.75f) }
                                true
                            }
                        }
                        Key.Menu -> {
                            onIntent(ReaderContract.Intent.ToggleMenu)
                            true
                        }
                        else -> false
                    }
                }
                .onSizeChanged { size = it }
                .pointerInput(state.settings.tapZone, state.settings.direction, state.menuVisible, screenHeightPx) {
                    detectTapGestures { offset ->
                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                        when (val action = tapActionAt(
                            x = offset.x / size.width,
                            y = offset.y / size.height,
                            mode = state.settings.tapZone,
                            direction = state.settings.direction,
                            menuVisible = state.menuVisible,
                            smallerTapZone = state.settings.smallerTapZone,
                            invert = state.settings.tapInvert,
                        )) {
                            TapAction.PREV -> scope.launch {
                                listState.animateScrollBy(-screenHeightPx * 0.75f)
                            }
                            TapAction.NEXT -> scope.launch {
                                listState.animateScrollBy(screenHeightPx * 0.75f)
                            }
                            TapAction.MENU -> onIntent(ReaderContract.Intent.ToggleMenu)
                            TapAction.CLOSE_MENU -> onIntent(ReaderContract.Intent.CloseMenu)
                            TapAction.NONE -> Unit
                        }
                    }
                },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(
                    if (state.settings.mode == ReadingMode.CONTINUOUS) 0.dp else 8.dp,
                ),
            ) {
                if (state.settings.chapterTransition && state.hasPreviousChapter) {
                    item(key = "prev-transition") {
                        TransitionCard(
                            currentTitle = state.chapterTitle.takeIf { it.isNotEmpty() },
                            targetTitle = state.previousChapterTitle,
                            label = "上一章",
                            onClick = { onIntent(ReaderContract.Intent.PrevChapter) },
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            contentColor = state.settings.theme.onCanvasColor(),
                        )
                    }
                }
                itemsIndexed(
                    items = List(state.pageCount) { it },
                    key = { _, index -> "page-$index" },
                ) { index, _ ->
                    WebtoonPageImage(
                        index = index,
                        state = state,
                        onIntent = onIntent,
                        horizontalPadding = padding,
                        viewportSize = size,
                        onTapAction = { action ->
                            when (action) {
                                TapAction.PREV -> scope.launch {
                                    listState.animateScrollBy(-screenHeightPx * 0.75f)
                                }
                                TapAction.NEXT -> scope.launch {
                                    listState.animateScrollBy(screenHeightPx * 0.75f)
                                }
                                TapAction.MENU -> onIntent(ReaderContract.Intent.ToggleMenu)
                                TapAction.CLOSE_MENU -> onIntent(ReaderContract.Intent.CloseMenu)
                                TapAction.NONE -> Unit
                            }
                        },
                    )
                }
                if (state.settings.chapterTransition && state.hasNextChapter) {
                    item(key = "next-transition") {
                        TransitionCard(
                            currentTitle = state.chapterTitle.takeIf { it.isNotEmpty() },
                            targetTitle = state.nextChapterTitle,
                            label = "下一章",
                            onClick = { onIntent(ReaderContract.Intent.NextChapter) },
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            contentColor = state.settings.theme.onCanvasColor(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebtoonPageImage(
    index: Int,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onTapAction: (TapAction) -> Unit,
    /** 视口尺寸（点按分区按全屏归一化，与外层 detector 一致）。 */
    viewportSize: IntSize,
) {
    val bytes = state.pages[index]
    val pageState = state.pageStates[index]
    // 图片在窗口中的 Y（点按分区按屏幕归一化，与外层 detector 一致）。
    var itemWindowY by remember { mutableStateOf(0f) }
    // 已知尺寸时先占位确定宽高比（防 LazyColumn 无界高度测成 0 + 滚动跳变）。
    val knownAspect = state.pageSizes[index]?.let { it.width.toFloat() / it.height }
    when {
        bytes != null -> CoilZoomAsyncImage(
            model = bytes,
            contentDescription = "第 ${index + 1} 页",
            modifier = Modifier
                .fillMaxWidth()
                .then(if (knownAspect != null) Modifier.aspectRatio(knownAspect) else Modifier)
                .padding(horizontal = horizontalPadding)
                .onGloballyPositioned { itemWindowY = it.positionInWindow().y },
            contentScale = ContentScale.FillWidth,
            colorFilter = state.settings.imageColorFilter(),
            zoomState = rememberCoilZoomState(),
            scrollBar = null,
            onTap = { offset ->
                if (viewportSize.width > 0 && viewportSize.height > 0) {
                    val screenY = itemWindowY + offset.y
                    val action = tapActionAt(
                        x = (offset.x / viewportSize.width).coerceIn(0f, 1f),
                        y = (screenY / viewportSize.height).coerceIn(0f, 1f),
                        mode = state.settings.tapZone,
                        direction = state.settings.direction,
                        menuVisible = state.menuVisible,
                        smallerTapZone = state.settings.smallerTapZone,
                        invert = state.settings.tapInvert,
                    )
                    onTapAction(action)
                }
            },
            onLongPress = {
                if (state.settings.readWithLongTap || state.menuVisible) {
                    onIntent(ReaderContract.Intent.ToggleMenu)
                }
            },
        )
        pageState is ReaderContract.PageLoadState.Error -> PageErrorPlaceholder(
            message = pageState.message,
            onRetry = { onIntent(ReaderContract.Intent.RetryPage(index)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .aspectRatio(3f / 4f),
        )
        else -> PageLoadingPlaceholder(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .aspectRatio(3f / 4f),
        )
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Chrome / sheets                                                                                 */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun ReaderChrome(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    onBack: () -> Unit,
    pageLabel: String,
) {
    // 对齐 Komikku ReaderAppBars：surface@3dp elevation 感知的半透明底（深色 .9 / 浅色 .95）。
    val barBg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
        alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f,
    )
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.menuVisible,
            enter = slideInVertically(animationSpec = tween(200)) { -it } + fadeIn(tween(150)),
            exit = slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut(tween(150)),
        ) {
            Column(
                Modifier
                    .background(barBg)
                    .clickable { onIntent(ReaderContract.Intent.ToggleMenu) },
            ) {
                ReaderTopBar(state, onIntent, onBack)
            }
        }
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(
            visible = state.menuVisible,
            enter = slideInVertically(animationSpec = tween(200)) { it } + fadeIn(tween(150)),
            exit = slideOutVertically(animationSpec = tween(200)) { it } + fadeOut(tween(150)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChapterNavigator(state, onIntent, pageLabel, barBg)
                ReaderBottomBar(state, onIntent, barBg)
            }
        }
    }
}

/**
 * 对齐 Komikku ReaderTopBar：透明底（底色由外层统一给）+ 左对齐双行标题 +
 * 仅书签 action（WebView/Browser/Share 原版即注释掉）。
 */
@Composable
private fun ReaderTopBar(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                state.mangaTitle.ifEmpty { state.chapterTitle },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = if (state.mangaTitle.isNotEmpty()) {
                state.chapterTitle
            } else {
                "第 ${state.chapterIndex + 1} / ${state.chapterCount} 章"
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onIntent(ReaderContract.Intent.ToggleBookmark) }) {
            Icon(
                if (state.bookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = "书签",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // 全文搜索仅小说正文可用（漫画是图片页，无字符坐标）。
        if (state.novel != null) {
            IconButton(onClick = { onIntent(ReaderContract.Intent.OpenSearch) }) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = "搜索正文",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = { onIntent(ReaderContract.Intent.ToggleHighlightMode) }) {
                Icon(
                    Icons.Outlined.Brush,
                    contentDescription = "划线模式",
                    tint = if (state.highlightMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    barBg: Color,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = barBg,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // 吞掉点按，避免穿透到底层 Viewer 触发翻页（对齐 Komikku pointerInput{}）。
        modifier = Modifier.pointerInput(Unit) {},
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { onIntent(ReaderContract.Intent.OpenChapterList) }) {
                Icon(Icons.Outlined.FormatListNumbered, contentDescription = "目录", tint = primary)
            }
            IconButton(onClick = { onIntent(ReaderContract.Intent.OpenBookmarks) }) {
                Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = "书签列表", tint = primary)
            }
            // 本地可用的直接开关（在线源的 WebView/Browser/Share/阅读模式/旋转在此无意义）。
            IconButton(onClick = { onIntent(ReaderContract.Intent.ToggleCropBorders) }) {
                Icon(
                    Icons.Outlined.Crop,
                    contentDescription = "裁边",
                    tint = if (state.settings.cropBorders) primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onIntent(ReaderContract.Intent.OpenSettings) }) {
                Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = primary)
            }
        }
    }
}

/**
 * 对齐 Komikku ChapterNavigator（横版）：
 * [上一话] [当前 •———• 总] [下一话]，RTL 互换；
 * pill 内无按钮（之前版本误放了两颗，已删除），文字 onSurface，按钮 primary。
 */
@Composable
private fun ChapterNavigator(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    pageLabel: String,
    barBg: Color,
) {
    val rtl = state.settings.direction == ReadingDirection.RTL
    val buttonColors = IconButtonDefaults.filledIconButtonColors(
        containerColor = barBg,
        disabledContainerColor = barBg,
        contentColor = MaterialTheme.colorScheme.primary,
    )
    val haptic = LocalHapticFeedback.current
    // 显式按阅读器方向处理，不跟随系统方向（对齐 Komikku CompositionLocalProvider Ltr）。
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                enabled = if (rtl) state.hasNextChapter else state.hasPreviousChapter,
                onClick = {
                    onIntent(
                        if (rtl) ReaderContract.Intent.NextChapter
                        else ReaderContract.Intent.PrevChapter,
                    )
                },
                colors = buttonColors,
            ) {
                Icon(
                    Icons.Outlined.SkipPrevious,
                    contentDescription = if (rtl) "下一章" else "上一章",
                )
            }

            if (state.pageCount > 1) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(barBg)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(contentAlignment = Alignment.CenterEnd) {
                        Text(pageLabel, color = MaterialTheme.colorScheme.onSurface)
                        // 透明总页数占宽，当前页变化时 pill 不抖动。
                        Text("${state.pageCount}", color = Color.Transparent)
                    }
                    val interactionSource = remember { MutableInteractionSource() }
                    val sliderDragged by interactionSource.collectIsDraggedAsState()
                    LaunchedEffect(state.pageIndex) {
                        if (sliderDragged) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                    Slider(
                        value = (state.pageIndex + 1).toFloat(),
                        onValueChange = {
                            val target = it.toInt()
                            if (target != state.pageIndex + 1) {
                                onIntent(ReaderContract.Intent.PageChanged(target - 1))
                            }
                        },
                        valueRange = 1f..state.pageCount.toFloat(),
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        "${state.pageCount}",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            FilledIconButton(
                enabled = if (rtl) state.hasPreviousChapter else state.hasNextChapter,
                onClick = {
                    onIntent(
                        if (rtl) ReaderContract.Intent.PrevChapter
                        else ReaderContract.Intent.NextChapter,
                    )
                },
                colors = buttonColors,
            ) {
                Icon(
                    Icons.Outlined.SkipNext,
                    contentDescription = if (rtl) "上一章" else "下一章",
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(pageLabel: String, total: Int) {
    if (total <= 0 || pageLabel.isEmpty()) return
    val text = "$pageLabel / $total"
    val style = MaterialTheme.typography.bodySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
    Box(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box {
            // 下垫深色描边，保证浅色页面上可读（对齐 Komikku #2D2D2D 4f stroke）。
            Text(
                text,
                style = style.copy(drawStyle = Stroke(width = 4f)),
                color = Color(0xFF2D2D2D),
            )
            Text(text, style = style, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 章节过渡卡（对齐 Komikku ChapterTransition）：
 * Next = 上 finished（当前）+ 下 next（目标）；Prev 反之；无目标章节时 OutlinedCard。
 */
@Composable
private fun TransitionCard(
    currentTitle: String?,
    targetTitle: String?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentColor: Color = Color.White,
) {
    Box(
        modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (targetTitle == null) {
                OutlinedCard(
                    colors = CardDefaults.outlinedCardColors(containerColor = Color.Transparent),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                currentTitle?.let {
                    Text(
                        it,
                        color = contentColor.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Text(
                    targetTitle,
                    color = contentColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClick) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterListSheet(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = { onIntent(ReaderContract.Intent.CloseChapterList) }) {
        Column(
            Modifier
                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
        ) {
            Text(
                "章节目录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp),
            )
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = state.chapters
                    .indexOfFirst { it.id == state.chapterId }
                    .coerceAtLeast(0),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 500.dp),
                state = listState,
            ) {
                itemsIndexed(state.chapters, key = { _, c -> c.id }) { index, chapter ->
                    val selected = chapter.id == state.chapterId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onIntent(ReaderContract.Intent.SelectChapter(chapter.id)) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            modifier = Modifier.width(36.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                chapter.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                            Text(
                                "第 ${index + 1} / ${state.chapters.size} 章",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 全文搜索条（小说正文；命中计数 + 上/下个 + 关闭，输入即搜即跳）。 */
@Composable
private fun ReaderSearchBar(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = state.searchQuery,
                onValueChange = { onIntent(ReaderContract.Intent.SearchQueryChanged(it)) },
                placeholder = { Text("搜索正文") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            val total = state.searchMatches.size
            Text(
                if (total == 0) "0/0" else "${state.searchIndex + 1}/$total",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            TextButton(
                onClick = { onIntent(ReaderContract.Intent.HighlightAllSearchMatches) },
                enabled = total > 0,
            ) {
                Text("标出")
            }
            IconButton(
                onClick = { onIntent(ReaderContract.Intent.SearchPrev) },
                enabled = total > 0,
            ) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上一个")
            }
            IconButton(
                onClick = { onIntent(ReaderContract.Intent.SearchNext) },
                enabled = total > 0,
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下一个")
            }
            IconButton(onClick = { onIntent(ReaderContract.Intent.CloseSearch) }) {
                Icon(Icons.Outlined.Close, contentDescription = "关闭搜索")
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkListSheet(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = { onIntent(ReaderContract.Intent.CloseBookmarks) }) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Text(
                "书签",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp),
            )
            val items = state.bookmarkList
            if (items.isEmpty()) {
                Text(
                    "暂无书签：在阅读页点右上角书签图标添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 500.dp),
                ) {
                    items(items, key = { it.id }) { bookmark ->
                        val chapterTitle = state.chapters
                            .firstOrNull { it.id == bookmark.chapterId }?.title
                            ?: bookmark.chapterId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onIntent(ReaderContract.Intent.JumpToBookmark(bookmark.id)) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    chapterTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                val excerpt = bookmark.excerpt
                                if (!excerpt.isNullOrBlank()) {
                                    Text(
                                        excerpt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val note = bookmark.note
                                if (!note.isNullOrBlank()) {
                                    Text(
                                        "笔记：$note",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onIntent(ReaderContract.Intent.StartEditNote(bookmark.id)) },
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = "编辑笔记")
                            }
                        }
                    }
                }
            }
            Text(
                "本章划线",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp),
            )
            val highlights = state.highlightList
            if (highlights.isEmpty()) {
                Text(
                    "暂无划线：打开划线模式后点段落添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    highlights.forEachIndexed { index, highlight ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onIntent(ReaderContract.Intent.JumpToHighlight(highlight.id)) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    highlight.excerpt ?: "第 ${index + 1} 处划线",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val highlightNote = highlight.note
                                if (!highlightNote.isNullOrBlank()) {
                                    Text(
                                        "笔记：$highlightNote",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onIntent(ReaderContract.Intent.StartEditHighlightNote(highlight.id)) },
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = "编辑划线笔记")
                            }
                            IconButton(onClick = { onIntent(ReaderContract.Intent.RemoveHighlight(highlight.id)) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "删除划线")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 书签笔记编辑器（纯本地文本，上限 2000 字，空串视为清除）。 */
@Composable
private fun BookmarkNoteDialog(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    val id = state.editingNoteId ?: return
    val bookmark = state.bookmarkList.firstOrNull { it.id == id } ?: return
    var text by remember(id) { mutableStateOf(bookmark.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = { onIntent(ReaderContract.Intent.CancelEditNote) },
        title = { Text("书签笔记") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it.take(2000) },
                placeholder = { Text("写下想法…") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onIntent(ReaderContract.Intent.SaveNote(text)) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(ReaderContract.Intent.CancelEditNote) }) {
                Text("取消")
            }
        },
    )
}

/** 划线笔记编辑器（纯本地文本，上限 2000 字，空串视为清除；与书签笔记同构）。 */
@Composable
private fun HighlightNoteDialog(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    val id = state.editingHighlightNoteId ?: return
    val highlight = state.highlightList.firstOrNull { it.id == id } ?: return
    var text by remember(id) { mutableStateOf(highlight.note.orEmpty()) }
    AlertDialog(
        onDismissRequest = { onIntent(ReaderContract.Intent.CancelEditHighlightNote) },
        title = { Text("划线笔记") },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it.take(2000) },
                placeholder = { Text("写下想法…") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onIntent(ReaderContract.Intent.SaveHighlightNote(text)) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = { onIntent(ReaderContract.Intent.CancelEditHighlightNote) }) {
                Text("取消")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    val settings = state.settings
    ModalBottomSheet(onDismissRequest = { onIntent(ReaderContract.Intent.CloseSettings) }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleMedium)
            // 按书作用域提示 + 恢复全局（仅本书有覆写时展示）。
            if (state.settingsScope != "global") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "本书单独设置生效中",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onIntent(ReaderContract.Intent.ResetBookSettings) }) {
                        Text("恢复全局设置")
                    }
                }
            }
            if (state.novel != null) {
                val t = state.settings.typography
                // 拖动期间只更新本地值，松手才提交：避免每帧写盘 + 触发全书重排。
                var fontSize by remember(t.fontSizeSp) { mutableStateOf(t.fontSizeSp) }
                var lineHeight by remember(t.lineHeightMultiple) { mutableStateOf(t.lineHeightMultiple) }
                var spacing by remember(t.paragraphSpacingDp) { mutableStateOf(t.paragraphSpacingDp) }
                var margin by remember(t.pageMarginDp) { mutableStateOf(t.pageMarginDp) }
                var indent by remember(t.paragraphIndentEm) { mutableStateOf(t.paragraphIndentEm) }
                Text(
                    "小说排版",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text("字号 ${fontSize.toInt()} sp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    onValueChangeFinished = {
                        onIntent(ReaderContract.Intent.NovelFontSizeChanged(fontSize))
                    },
                    valueRange = 12f..30f,
                    steps = 17,
                )
                Text("行距 $lineHeight 倍", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = lineHeight,
                    onValueChange = { lineHeight = it },
                    onValueChangeFinished = {
                        onIntent(ReaderContract.Intent.NovelLineHeightChanged(lineHeight))
                    },
                    valueRange = 1f..2.5f,
                    steps = 14,
                )
                Text("段距 ${spacing.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = spacing,
                    onValueChange = { spacing = it },
                    onValueChangeFinished = {
                        onIntent(ReaderContract.Intent.NovelParagraphSpacingChanged(spacing))
                    },
                    valueRange = 0f..48f,
                    steps = 23,
                )
                Text("页边距 ${margin.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = margin,
                    onValueChange = { margin = it },
                    onValueChangeFinished = {
                        onIntent(ReaderContract.Intent.NovelPageMarginChanged(margin))
                    },
                    valueRange = 0f..64f,
                    steps = 31,
                )
                Text("首行缩进 ${indent} em", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = indent,
                    onValueChange = { indent = it },
                    onValueChangeFinished = {
                        onIntent(ReaderContract.Intent.NovelParagraphIndentChanged(indent))
                    },
                    valueRange = 0f..4f,
                    steps = 39,
                )
            }
            SettingsSection("阅读模式") {
                FilterChip(
                    selected = settings.mode == ReadingMode.PAGED,
                    onClick = { onIntent(ReaderContract.Intent.ModeChanged(ReadingMode.PAGED)) },
                    label = { Text("单页") },
                )
                FilterChip(
                    selected = settings.mode == ReadingMode.CONTINUOUS,
                    onClick = { onIntent(ReaderContract.Intent.ModeChanged(ReadingMode.CONTINUOUS)) },
                    label = { Text("长条") },
                )
            }
            SettingsSection("翻页方向") {
                FilterChip(
                    selected = settings.direction == ReadingDirection.LTR,
                    onClick = { onIntent(ReaderContract.Intent.DirectionChanged(ReadingDirection.LTR)) },
                    label = { Text("左→右") },
                )
                FilterChip(
                    selected = settings.direction == ReadingDirection.RTL,
                    onClick = { onIntent(ReaderContract.Intent.DirectionChanged(ReadingDirection.RTL)) },
                    label = { Text("右→左") },
                )
                FilterChip(
                    selected = settings.direction == ReadingDirection.VERTICAL,
                    onClick = { onIntent(ReaderContract.Intent.DirectionChanged(ReadingDirection.VERTICAL)) },
                    label = { Text("纵向") },
                )
            }
            SettingsSection("图片缩放") {
                ImageScaleType.entries.forEach { scale ->
                    FilterChip(
                        selected = settings.scaleType == scale,
                        onClick = { onIntent(ReaderContract.Intent.ScaleChanged(scale)) },
                        label = { Text(scale.displayName()) },
                    )
                }
            }
            SettingsSection("点按区域") {
                TapZoneMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.tapZone == mode,
                        onClick = { onIntent(ReaderContract.Intent.TapZoneChanged(mode)) },
                        label = { Text(mode.displayName()) },
                    )
                }
            }
            SettingsSection("点按反转") {
                TapInvertMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.tapInvert == mode,
                        onClick = { onIntent(ReaderContract.Intent.TapInvertChanged(mode)) },
                        label = { Text(mode.displayName()) },
                    )
                }
            }
            SettingsSection("主题") {
                ReaderTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = settings.theme == theme,
                        onClick = { onIntent(ReaderContract.Intent.ThemeChanged(theme)) },
                        label = { Text(theme.displayName()) },
                    )
                }
            }
            SettingsSwitch("双页（横屏）", settings.doublePage) {
                onIntent(ReaderContract.Intent.ToggleDoublePage)
            }
            SettingsSwitch("双页顺序取反", settings.invertDoublePages) {
                onIntent(ReaderContract.Intent.ToggleInvertDoublePages)
            }
            SettingsSwitch("更小的点按热区", settings.smallerTapZone) {
                onIntent(ReaderContract.Intent.ToggleSmallerTapZone)
            }
            SettingsSwitch("长按唤起菜单", settings.readWithLongTap) {
                onIntent(ReaderContract.Intent.ToggleReadWithLongTap)
            }
            SettingsSwitch("音量键翻页", settings.volumeKeysEnabled) {
                onIntent(ReaderContract.Intent.ToggleVolumeKeys)
            }
            SettingsSwitch("音量键反转", settings.volumeKeysInverted) {
                onIntent(ReaderContract.Intent.ToggleVolumeKeysInverted)
            }
            SettingsSwitch("灰度", settings.grayscale) {
                onIntent(ReaderContract.Intent.ToggleGrayscale)
            }
            SettingsSwitch("显示页码", settings.showPageNumber) {
                onIntent(ReaderContract.Intent.TogglePageNumber)
            }
            Text(
                "长条侧边距 ${settings.webtoonSidePaddingPercent}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = settings.webtoonSidePaddingPercent.toFloat(),
                onValueChange = {
                    onIntent(ReaderContract.Intent.WebtoonPaddingChanged(it.toInt()))
                },
                valueRange = 0f..25f,
                steps = 24,
            )
            Text(
                "亮度遮罩 ${settings.dimLevel}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = settings.dimLevel.toFloat(),
                onValueChange = {
                    onIntent(ReaderContract.Intent.DimLevelChanged(it.toInt()))
                },
                valueRange = 0f..60f,
                steps = 59,
            )
            SettingsSwitch("反色", settings.invertedColors) {
                onIntent(ReaderContract.Intent.ToggleInvertedColors)
            }
            SettingsSection("颜色滤镜") {
                FilterChip(
                    selected = settings.tintColorArgb == null,
                    onClick = { onIntent(ReaderContract.Intent.TintColorChanged(null)) },
                    label = { Text("无") },
                )
                TintChip("暖黄", 0x22FFB300, settings.tintColorArgb, onIntent)
                TintChip("冷蓝", 0x223366FF, settings.tintColorArgb, onIntent)
                TintChip("夜", 0x44000000, settings.tintColorArgb, onIntent)
            }
            SettingsSwitch("自动滚动", settings.autoScroll) {
                onIntent(ReaderContract.Intent.ToggleAutoScroll)
            }
            Text(
                "自滚间隔 ${settings.autoScrollIntervalSec} 秒",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = settings.autoScrollIntervalSec,
                onValueChange = {
                    onIntent(ReaderContract.Intent.AutoScrollIntervalChanged(it))
                },
                valueRange = 1f..10f,
                steps = 8,
            )
            SettingsSwitch("自动裁边", settings.cropBorders) {
                onIntent(ReaderContract.Intent.ToggleCropBorders)
            }
            SettingsSwitch("翻页闪屏", settings.flashOnPageChange) {
                onIntent(ReaderContract.Intent.ToggleFlashOnChange)
            }
            Text(
                "闪屏时长 ${settings.flashDurationMs} 毫秒",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = settings.flashDurationMs.toFloat(),
                onValueChange = {
                    onIntent(ReaderContract.Intent.FlashDurationChanged(it.toInt()))
                },
                valueRange = 100f..1500f,
                steps = 13,
            )
            SettingsSwitch("白色闪屏", settings.flashWhite) {
                onIntent(ReaderContract.Intent.ToggleFlashWhite)
            }
        }
    }
}

@Composable
private fun TintChip(
    label: String,
    argb: Long,
    current: Long?,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    FilterChip(
        selected = current == argb,
        onClick = { onIntent(ReaderContract.Intent.TintColorChanged(argb)) },
        label = { Text(label) },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsSwitch(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Placeholders / helpers                                                                          */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("加载失败：$message", color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("重试") }
            Button(onClick = onBack) { Text("返回") }
        }
    }
}

@Composable
private fun PageLoadingPlaceholder(modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun PageErrorPlaceholder(message: String, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(onClick = onRetry)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "图片加载失败",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            message,
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 3,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text("点击重试", color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** 兼容旧状态（未结构化正文）：纯文本降级视图；结构化正文走 NovelReader。 */
@Composable
private fun PlainArticleContent(article: String, settings: ReadingConfig) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            article,
            color = if (settings.theme == ReaderTheme.WHITE) Color.Black else Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun handleTap(
    offset: Offset,
    size: IntSize,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    val action = tapActionAt(
        x = offset.x / size.width,
        y = offset.y / size.height,
        mode = state.settings.tapZone,
        direction = state.settings.direction,
        menuVisible = state.menuVisible,
        smallerTapZone = state.settings.smallerTapZone,
        invert = state.settings.tapInvert,
    )
    when (action) {
        TapAction.PREV -> onIntent(ReaderContract.Intent.PrevPage)
        TapAction.NEXT -> onIntent(ReaderContract.Intent.NextPage)
        TapAction.MENU -> onIntent(ReaderContract.Intent.ToggleMenu)
        TapAction.CLOSE_MENU -> onIntent(ReaderContract.Intent.CloseMenu)
        TapAction.NONE -> Unit
    }
}

private fun ReaderTheme.canvasColor(): Color = when (this) {
    ReaderTheme.BLACK -> Color(0xFF000000)
    ReaderTheme.DARK -> Color(0xFF15161A)
    ReaderTheme.WHITE -> Color(0xFFFAFAFA)
}

private fun ReaderTheme.onCanvasColor(): Color = when (this) {
    ReaderTheme.WHITE -> Color.Black
    else -> Color.White
}

private fun ReaderTheme.displayName(): String = when (this) {
    ReaderTheme.BLACK -> "黑"
    ReaderTheme.DARK -> "深灰"
    ReaderTheme.WHITE -> "白"
}

private fun ImageScaleType.displayName(): String = when (this) {
    ImageScaleType.FIT -> "适应"
    ImageScaleType.FIT_WIDTH -> "适宽"
    ImageScaleType.FIT_HEIGHT -> "适高"
    ImageScaleType.ORIGINAL -> "原图"
    ImageScaleType.SMART_FIT -> "智能"
}

private fun ImageScaleType.toContentScale(): ContentScale = when (this) {
    ImageScaleType.FIT, ImageScaleType.SMART_FIT -> ContentScale.Fit
    ImageScaleType.FIT_WIDTH -> ContentScale.FillWidth
    ImageScaleType.FIT_HEIGHT -> ContentScale.FillHeight
    ImageScaleType.ORIGINAL -> ContentScale.None
}

private fun TapZoneMode.displayName(): String = when (this) {
    TapZoneMode.LEFT_RIGHT -> "左右"
    TapZoneMode.L_SHAPE -> "L 形"
    TapZoneMode.EDGE -> "边缘"
    TapZoneMode.KINDLISH -> "Kindlish"
    TapZoneMode.DISABLED -> "关闭"
}

private fun TapInvertMode.displayName(): String = when (this) {
    TapInvertMode.NONE -> "无"
    TapInvertMode.HORIZONTAL -> "水平"
    TapInvertMode.VERTICAL -> "垂直"
    TapInvertMode.BOTH -> "双向"
}

private fun ReadingMode.displayName(): String = when (this) {
    ReadingMode.PAGED -> "分页"
    ReadingMode.CONTINUOUS -> "长条"
}

private fun ReadingDirection.displayName(): String = when (this) {
    ReadingDirection.LTR -> "左→右"
    ReadingDirection.RTL -> "右→左"
    ReadingDirection.VERTICAL -> "纵向"
}

private fun ReadingConfig.imageColorFilter(): ColorFilter? {
    var matrix: ColorMatrix? = null
    if (grayscale) {
        matrix = ColorMatrix().apply { setToSaturation(0f) }
    }
    if (invertedColors) {
        // 反色矩阵（对齐 Komikku invertedColors；与灰度矩阵相乘叠加）。
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        matrix = if (matrix == null) invert else matrix.apply { timesAssign(invert) }
    }
    return matrix?.let { ColorFilter.colorMatrix(it) }
}
