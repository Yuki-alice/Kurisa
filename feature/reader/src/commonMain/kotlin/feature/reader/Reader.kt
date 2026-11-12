package feature.reader

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import core.reader.TapZoneMode
import core.reader.buildSpreads
import core.reader.spreadIndexOfPage
import core.reader.tapActionAt
import data.images.ImageFetcher
import data.images.SourceImageFetcher
import data.localimport.LocalImageFetcher
import data.localimport.LocalSourceFileSystem
import data.network.CookieStore
import data.network.SourceHttpClient
import data.network.defaultHttpClient
import data.repository.ProgressRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 阅读器（漫画 & 小说共用外壳，内部分支；Viewer 由阅读模式选择）。 */
@Serializable
data class ReaderRoute(val workId: String, val chapterId: String) : NavKey

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
) {
    // 网络栈装配（Koin M5 前手写：CookieStore 按 composition 记忆；
    // 会话级共享随 M5 DI 落地；demo 源无鉴权需求）。
    val cookies = remember { CookieStore() }
    val images: ImageFetcher = remember(localFs) {
        val network = SourceImageFetcher(SourceHttpClient(defaultHttpClient(), cookies) { 0L })
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
        ),
    )
    ReaderScreenVm(workId = workId, onBack = onBack, vm = vm)
}

/** VM 已就绪入口（预览/测试直接供 VM，不碰网络装配）。 */
@Composable
fun ReaderScreenVm(
    workId: String,
    onBack: () -> Unit,
    vm: ReaderViewModel,
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
        )
    }
}

@Composable
fun ReaderContent(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    Box(
        modifier
            .fillMaxSize()
            .background(settings.theme.canvasColor()),
    ) {
        when {
            state.article != null -> NovelContent(state.article, state.settings)
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

        if (state.menuVisible) {
            ReaderChrome(state, onIntent, onBack)
        } else if (state.article == null && settings.showPageNumber && state.pageCount > 0) {
            PageIndicator(state)
        }

        if (state.chapterListVisible) {
            ChapterListSheet(state, onIntent)
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
                    Key.DirectionLeft, Key.DirectionUp, Key.PageUp, Key.VolumeUp -> {
                        onIntent(ReaderContract.Intent.PrevPage)
                        true
                    }
                    Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar,
                    Key.VolumeDown,
                    -> {
                        onIntent(ReaderContract.Intent.NextPage)
                        true
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
        val spreads = remember(state.pageCount, doublePage) {
            buildSpreads(state.pageCount, doublePage)
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
            title = previousTitle(state),
            label = "上一章",
            onClick = { onIntent(ReaderContract.Intent.PrevChapter) },
            contentColor = state.settings.theme.onCanvasColor(),
        )
        PagerItem.NextTransition -> TransitionCard(
            title = nextTitle(state),
            label = "下一章",
            onClick = { onIntent(ReaderContract.Intent.NextChapter) },
            contentColor = state.settings.theme.onCanvasColor(),
        )
        is PagerItem.Spread -> Row(Modifier.fillMaxSize()) {
            // RTL 双页：页 1 应在右侧，页 2 在左侧。
            val pages = if (state.settings.direction == ReadingDirection.RTL) {
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
            onLongPress = { onIntent(ReaderContract.Intent.ToggleMenu) },
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
                        Key.DirectionUp, Key.PageUp, Key.VolumeUp -> {
                            scope.launch { listState.animateScrollBy(-screenHeightPx * 0.75f) }
                            true
                        }
                        Key.DirectionDown, Key.PageDown, Key.Spacebar, Key.VolumeDown -> {
                            scope.launch { listState.animateScrollBy(screenHeightPx * 0.75f) }
                            true
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
                            title = previousTitle(state),
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
                    )
                }
                if (state.settings.chapterTransition && state.hasNextChapter) {
                    item(key = "next-transition") {
                        TransitionCard(
                            title = nextTitle(state),
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
) {
    val bytes = state.pages[index]
    val pageState = state.pageStates[index]
    when {
        bytes != null -> AsyncImage(
            model = bytes,
            contentDescription = "第 ${index + 1} 页",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            contentScale = ContentScale.FillWidth,
            colorFilter = state.settings.imageColorFilter(),
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
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = Color.Black.copy(alpha = 0.82f), contentColor = Color.White) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.chapterTitle.ifEmpty { "第 ${state.chapterIndex + 1} 章" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        "${state.chapterIndex + 1} / ${state.chapterCount} 章",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                TextButton(onClick = { onIntent(ReaderContract.Intent.OpenChapterList) }) { Text("目录") }
                TextButton(onClick = { onIntent(ReaderContract.Intent.OpenSettings) }) { Text("设置") }
            }
        }
        Spacer(Modifier.weight(1f))
        Surface(color = Color.Black.copy(alpha = 0.82f), contentColor = Color.White) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onIntent(ReaderContract.Intent.PrevChapter) },
                    enabled = state.hasPreviousChapter,
                ) { Text("上一章") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.pageCount > 0) {
                            "${state.pageIndex + 1} / ${state.pageCount}"
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        state.settings.mode.displayName() + " · " +
                            state.settings.direction.displayName(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                TextButton(
                    onClick = { onIntent(ReaderContract.Intent.NextChapter) },
                    enabled = state.hasNextChapter,
                ) { Text("下一章") }
            }
        }
    }
}

@Composable
private fun PageIndicator(state: ReaderContract.State) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = "${state.pageIndex + 1} / ${state.pageCount}",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun TransitionCard(
    title: String?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentColor: Color = Color.White,
) {
    Box(
        modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title ?: label,
                color = contentColor.copy(alpha = 0.9f),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(label) }
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
                .fillMaxHeight(0.7f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                "章节目录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
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
                        Text(
                            chapter.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
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
        }
    }
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

@Composable
private fun NovelContent(article: String, settings: ReadingConfig) {
    // M4 做排版分页；此处按 format 做最小分流，避免 HTML 标签原样显示。
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
    )
    when (action) {
        TapAction.PREV -> onIntent(ReaderContract.Intent.PrevPage)
        TapAction.NEXT -> onIntent(ReaderContract.Intent.NextPage)
        TapAction.MENU -> onIntent(ReaderContract.Intent.ToggleMenu)
        TapAction.CLOSE_MENU -> onIntent(ReaderContract.Intent.CloseMenu)
        TapAction.NONE -> Unit
    }
}

private fun previousTitle(state: ReaderContract.State): String? =
    state.chapters.getOrNull(state.chapterIndex - 1)?.title

private fun nextTitle(state: ReaderContract.State): String? =
    state.chapters.getOrNull(state.chapterIndex + 1)?.title

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
    TapZoneMode.DISABLED -> "关闭"
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

private fun ReadingConfig.imageColorFilter(): ColorFilter? =
    if (grayscale) {
        ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(0f) },
        )
    } else {
        null
    }
