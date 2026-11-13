package feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import core.model.NovelBlockKind
import core.reader.LayoutKey
import core.reader.LineMetrics
import core.reader.MeasuredLine
import core.reader.NovelPage
import core.reader.NovelPageBlock
import core.reader.NovelPaginator
import core.reader.NovelStyleSpec
import core.reader.NovelTypography
import core.reader.NovelViewport
import core.reader.ReadingDirection
import core.reader.ReadingMode
import core.reader.ReaderTheme
import core.reader.TapAction
import core.reader.TextMeasure
import core.reader.tapActionAt
import data.images.ImageFetcher
import data.images.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 小说 Viewer（M4）。
 *
 * - 测量实现是本模块唯一接触 Compose 文本排版处：`rememberTextMeasurer()` 从
 *   CompositionLocals 取得 FontFamily.Resolver / Density，避免 core 层新增 expect/actual（ADR-006）；
 * - 分页结果按 LayoutKey 复用；字号/窗口/密度变化自动触发局部重排；
 * - 文内图片只经调用方注入的 [ImageFetcher] 加载：https 交给网络出口做域名白名单 +
 *   逐跳重定向复核 + 配对 UA/Cookie，本地 `!/` 归档路径交给本地抓取器；
 *   相对路径、http、file 等一律拦截为占位符，不绕过 UrlPolicy。
 */
@Composable
internal fun NovelReader(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    modifier: Modifier = Modifier,
    images: ImageFetcher? = null,
    domains: Set<String> = emptySet(),
) {
    val doc = state.novel ?: return
    val settings = state.settings
    val typography = settings.typography
    val density = LocalDensity.current
    val measure = rememberComposeTextMeasure()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = with(density) {
            val margin = typography.pageMarginDp.dp.toPx()
            NovelViewport(
                widthPx = (maxWidth.toPx() - 2 * margin).toInt().coerceAtLeast(1),
                heightPx = (maxHeight.toPx() - 2 * margin).toInt().coerceAtLeast(1),
            )
        }
        val key = LayoutKey(
            chapterId = doc.chapterId,
            contentHash = doc.contentHash,
            viewport = viewport,
            typography = typography,
            density = density.density,
            fontFamilyId = "default",
            localeTag = "",
        )
        val signature = remember(key) { key.signature() }
        // 分批发布：首帧先绘制占位，排版在组合后发布，避免超长章阻塞首帧。
        // TextMeasurer 仍在主线程使用（CompositionLocals 要求），只是推迟到首帧后。
        val pages by produceState<List<NovelPage>?>(initialValue = null, key1 = signature, key2 = doc) {
            value = NovelPaginator(measure).paginate(doc, viewport, typography, key)
        }
        LaunchedEffect(pages?.size, signature) {
            val ready = pages ?: return@LaunchedEffect
            onIntent(ReaderContract.Intent.NovelPagesReady(ready.size, signature))
        }
        val readyPages = pages
        if (readyPages == null) {
            NovelPaginatingPlaceholder(state.settings.theme)
            return@BoxWithConstraints
        }
        val initialPage = remember(state.novelAnchor, readyPages) {
            NovelPaginator.pageOfCharOffset(readyPages, state.novelAnchor)
        }
        // 业务头随 State 发布（key=effectiveUrl），Viewer 取图时带上。
        val imageHeaders = state.novelImageHeaders
        if (settings.mode == ReadingMode.CONTINUOUS) {
            NovelContinuousReader(readyPages, initialPage, state, onIntent, signature, typography, images, domains, imageHeaders)
        } else {
            NovelPagedReader(readyPages, initialPage, state, onIntent, signature, typography, images, domains, imageHeaders)
        }
    }
}

@Composable
private fun NovelPagedReader(
    pages: List<NovelPage>,
    initialPage: Int,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    signature: String,
    typography: NovelTypography,
    images: ImageFetcher?,
    domains: Set<String>,
    imageHeaders: Map<String, Map<String, String>> = emptyMap(),
) {
    if (pages.isEmpty()) {
        NovelPaginatingPlaceholder(state.settings.theme)
        return
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    // 锚点门控：分批发布期间 snapshotFlow 首发（第 0 页）不得覆盖已恢复的 novelAnchor；
    // 待锚点所在页就位并滚动到位后，才开始发射 PageChanged/NovelAnchorChanged。
    var anchorSynced by remember(signature) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(pages, initialPage, signature) {
        if (!anchorSynced) {
            val target = initialPage.coerceIn(0, pages.lastIndex)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
            anchorSynced = true
        }
    }
    // 外部进度恢复（目录跳转、按键翻页）同步到 Pager；用户拖拽中不打断。
    LaunchedEffect(state.pageIndex, pages, anchorSynced) {
        if (!anchorSynced) return@LaunchedEffect
        val desired = state.pageIndex.coerceIn(0, pages.lastIndex)
        if (!pagerState.isScrollInProgress && pagerState.currentPage != desired) {
            pagerState.scrollToPage(desired)
        }
    }
    // 同章书签定位：锚点门控通过后再滚动，避免与恢复滚动打架；执行后回收请求。
    val pagedSeek = state.bookmarkSeek
    LaunchedEffect(pagedSeek, anchorSynced, pages) {
        val s = pagedSeek ?: return@LaunchedEffect
        if (!anchorSynced || pages.isEmpty()) return@LaunchedEffect
        val target = NovelPaginator.pageOfCharOffset(pages, s.charOffset).coerceIn(0, pages.lastIndex)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
        onIntent(ReaderContract.Intent.BookmarkSeekHandled(s.nonce))
    }
    LaunchedEffect(pagerState, pages, signature, anchorSynced) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { index ->
                if (!anchorSynced) return@collect
                val page = pages.getOrNull(index) ?: return@collect
                onIntent(ReaderContract.Intent.PageChanged(index))
                onIntent(ReaderContract.Intent.NovelAnchorChanged(page.charStart, signature))
            }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = state.settings.direction == ReadingDirection.RTL,
        beyondViewportPageCount = 1,
        // 划线模式为标记模式：点按/拖拽均不翻页，Viewer 手势一并关闭避免抢夺选区。
        userScrollEnabled = !state.highlightMode,
    ) { index ->
        NovelPageContent(
            page = pages[index],
            state = state,
            onIntent = onIntent,
            typography = typography,
            images = images,
            domains = domains,
            imageHeaders = imageHeaders,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun NovelContinuousReader(
    pages: List<NovelPage>,
    initialPage: Int,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    signature: String,
    typography: NovelTypography,
    images: ImageFetcher?,
    domains: Set<String>,
    imageHeaders: Map<String, Map<String, String>> = emptyMap(),
) {
    if (pages.isEmpty()) {
        NovelPaginatingPlaceholder(state.settings.theme)
        return
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialPage.coerceIn(0, pages.lastIndex),
    )
    // 锚点门控（同 Paged）：锚点页就位前不发射，避免首发 0 覆盖恢复进度。
    var anchorSynced by remember(signature) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(pages, initialPage, signature) {
        if (!anchorSynced) {
            val target = initialPage.coerceIn(0, pages.lastIndex)
            if (listState.firstVisibleItemIndex != target) listState.scrollToItem(target)
            anchorSynced = true
        }
    }
    LaunchedEffect(listState, pages, signature, anchorSynced) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (!anchorSynced) return@collect
                val page = pages.getOrNull(index) ?: return@collect
                onIntent(ReaderContract.Intent.PageChanged(index))
                onIntent(ReaderContract.Intent.NovelAnchorChanged(page.charStart, signature))
            }
    }
    // 同章书签定位（同 Paged 门控语义）。
    val continuousSeek = state.bookmarkSeek
    LaunchedEffect(continuousSeek, anchorSynced, pages) {
        val s = continuousSeek ?: return@LaunchedEffect
        if (!anchorSynced || pages.isEmpty()) return@LaunchedEffect
        val target = NovelPaginator.pageOfCharOffset(pages, s.charOffset).coerceIn(0, pages.lastIndex)
        if (listState.firstVisibleItemIndex != target) listState.scrollToItem(target)
        onIntent(ReaderContract.Intent.BookmarkSeekHandled(s.nonce))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = typography.pageMarginDp.dp),
        // 划线模式为标记模式：同上，关闭滚动避免抢夺选区。
        userScrollEnabled = !state.highlightMode,
    ) {
        itemsIndexed(pages, key = { index, page -> "$index:${page.charStart}" }) { _, page ->
            NovelPageContent(
                page = page,
                state = state,
                onIntent = onIntent,
                typography = typography,
                images = images,
                domains = domains,
                imageHeaders = imageHeaders,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NovelPageContent(
    page: NovelPage,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    typography: NovelTypography,
    images: ImageFetcher?,
    domains: Set<String>,
    imageHeaders: Map<String, Map<String, String>> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val foreground = if (state.settings.theme == ReaderTheme.WHITE) {
        Color(0xFF1A1A1A)
    } else {
        Color(0xFFF2F2F2)
    }
    val density = LocalDensity.current.density
    Column(
        modifier
            .background(novelCanvasColor(state.settings.theme))
            .pointerInput(state.settings, state.menuVisible) {
                val bounds = size
                detectTapGestures(onTap = { offset ->
                    handleNovelTap(offset, bounds, state, onIntent)
                })
            }
            .padding(
                horizontal = typography.pageMarginDp.dp,
                vertical = (typography.pageMarginDp / 2).dp,
            )
            .fillMaxWidth(),
    ) {
        // 划线模式：点段落即标/取消（不翻页）；关闭时点按走原翻页/菜单链路。
        val blockTap: ((NovelPageBlock) -> Unit)? = if (state.highlightMode) { block ->
            onIntent(
                ReaderContract.Intent.ToggleBlockHighlight(
                    block.charStart,
                    block.charStart + block.text.length,
                ),
            )
        } else {
            null
        }
        for (block in page.blocks) {
            when (block.kind) {
                NovelBlockKind.IMAGE -> NovelImage(
                    url = block.imageUrl,
                    images = images,
                    domains = domains,
                    imageHeaders = imageHeaders,
                    tint = foreground,
                )
                NovelBlockKind.HEADING -> NovelRichText(
                    block = block,
                    state = state,
                    style = TextStyle(
                        color = foreground,
                        fontSize = (typography.fontSizeSp * 1.25f).sp,
                        lineHeight = (typography.fontSizeSp * typography.lineHeightMultiple * 1.25f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.padding(top = (typography.paragraphSpacingDp / 2).dp),
                    onHighlight = blockTap?.let { tap -> { tap(block) } },
                    onHighlightWord = if (state.highlightMode) { s: Int, e: Int ->
                        onIntent(
                            ReaderContract.Intent.ToggleBlockHighlight(block.charStart + s, block.charStart + e),
                        )
                    } else null,
                    onHighlightRange = if (state.highlightMode) { s: Int, e: Int ->
                        onIntent(
                            ReaderContract.Intent.UpdateNovelSelection(block.charStart + s, block.charStart + e),
                        )
                    } else null,
                    onHighlightRangeCancel = if (state.highlightMode) {
                        { onIntent(ReaderContract.Intent.ClearNovelSelection) }
                    } else null,
                )
                NovelBlockKind.QUOTE -> NovelRichText(
                    block = block,
                    state = state,
                    style = TextStyle(
                        color = foreground.copy(alpha = 0.85f),
                        fontSize = typography.fontSizeSp.sp,
                        lineHeight = (typography.fontSizeSp * typography.lineHeightMultiple).sp,
                        fontStyle = FontStyle.Italic,
                    ),
                    modifier = Modifier.padding(
                        start = 12.dp,
                        top = (typography.paragraphSpacingDp / 2).dp,
                    ),
                    onHighlight = blockTap?.let { tap -> { tap(block) } },
                    onHighlightWord = if (state.highlightMode) { s: Int, e: Int ->
                        onIntent(
                            ReaderContract.Intent.ToggleBlockHighlight(block.charStart + s, block.charStart + e),
                        )
                    } else null,
                    onHighlightRange = if (state.highlightMode) { s: Int, e: Int ->
                        onIntent(
                            ReaderContract.Intent.UpdateNovelSelection(block.charStart + s, block.charStart + e),
                        )
                    } else null,
                    onHighlightRangeCancel = if (state.highlightMode) {
                        { onIntent(ReaderContract.Intent.ClearNovelSelection) }
                    } else null,
                )
                NovelBlockKind.PARAGRAPH, NovelBlockKind.LIST -> NovelRichText(
                    block = block,
                    state = state,
                    style = TextStyle(
                        color = foreground,
                        fontSize = typography.fontSizeSp.sp,
                        lineHeight = (typography.fontSizeSp * typography.lineHeightMultiple).sp,
                        textAlign = TextAlign.Justify,
                    ),
                    modifier = Modifier
                        .padding(
                            start = (block.indentPx / density).dp,
                            top = (typography.paragraphSpacingDp / 2).dp,
                        )
                        .fillMaxWidth(),
                    onHighlight = blockTap?.let { tap -> { tap(block) } },
                    onHighlightWord = if (state.highlightMode) { s: Int, e: Int ->
                        onIntent(
                            ReaderContract.Intent.ToggleBlockHighlight(block.charStart + s, block.charStart + e),
                        )
                    } else null,
                    onHighlightRange = if (state.highlightMode) { s: Int, e: Int ->
                        onIntent(
                            ReaderContract.Intent.UpdateNovelSelection(block.charStart + s, block.charStart + e),
                        )
                    } else null,
                    onHighlightRangeCancel = if (state.highlightMode) {
                        { onIntent(ReaderContract.Intent.ClearNovelSelection) }
                    } else null,
                )
            }
        }
    }
}

/**
 * 受控图片渲染：只接受 https 或本地归档虚拟路径，交给注入的 [ImageFetcher]。
 * 网络出口（SourceImageFetcher → SourceHttpClient）负责 UrlPolicy 白名单、重定向
 * 逐跳复核与 cf_clearance 配对 UA；本层只透传源业务头（Referer 类，已脱敏），
 * UA/Cookie 一律由宿主出口统一注入，调用方不得自行补传。
 */
@Composable
private fun NovelImage(
    url: String?,
    images: ImageFetcher?,
    domains: Set<String>,
    tint: Color,
    imageHeaders: Map<String, Map<String, String>> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val safe = url?.takeIf(::isAllowedNovelImageUrl)
    if (safe == null || images == null) {
        Text(
            text = "[图片已拦截]",
            color = tint.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.padding(vertical = 4.dp),
        )
        return
    }
    val headers = imageHeaders[safe].orEmpty()
    val bytes by produceState<ByteArray?>(
        initialValue = null,
        key1 = safe,
        key2 = images,
        key3 = domains to headers,
    ) {
        value = runCatching { images.fetch(ImageRequest(safe, domains, headers)).bytes }.getOrNull()
    }
    if (bytes == null) {
        Text(
            text = "[图片]",
            color = tint.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.padding(vertical = 4.dp),
        )
    } else {
        AsyncImage(
            model = bytes,
            contentDescription = "正文图片",
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .padding(vertical = 4.dp),
        )
    }
}

/** 相对路径/明文 http/file 等一律拒绝，避免本地 VFS 误读与白名单绕过。 */
internal fun isAllowedNovelImageUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (lower.startsWith("https://")) return true
    return "!/" in url && !lower.contains("://")
}

private fun handleNovelTap(
    offset: Offset,
    size: IntSize,
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    if (size.width <= 0 || size.height <= 0) return
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

/**
 * 富文本 + 三层高亮：行内样式 → 搜索命中（当前主色容器底、其余次色）→ 划线（第三色容器底）。
 * 区间均为文档坐标，映射到当前页块坐标；无 span、无搜索、无划线时原样返回纯文本。
 */
@Composable
private fun highlightMatches(block: NovelPageBlock, state: ReaderContract.State): AnnotatedString {
    val matches = state.searchMatches
    val text = block.text
    if (block.spans.isEmpty() && matches.isEmpty() && state.highlightList.isEmpty()) {
        return AnnotatedString(text)
    }
    val current = state.searchIndex
    val colors = MaterialTheme.colorScheme
    return buildAnnotatedString {
        append(text)
        for (s in block.spans) {
            val start = s.start.coerceIn(0, text.length)
            val end = s.end.coerceIn(0, text.length)
            if (end <= start) continue
            addStyle(
                when (s.style) {
                    core.model.NovelInlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    core.model.NovelInlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    core.model.NovelInlineStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
                    core.model.NovelInlineStyle.STRIKETHROUGH ->
                        SpanStyle(textDecoration = TextDecoration.LineThrough)
                    core.model.NovelInlineStyle.LINK -> SpanStyle(
                        color = colors.primary,
                        textDecoration = TextDecoration.Underline,
                    )
                },
                start,
                end,
            )
            // 外链点击走 LinkAnnotation.Url（Text 原生处理，经 LocalUriHandler 打开，不经过 WebView）。
            if (s.style == core.model.NovelInlineStyle.LINK) {
                s.payload?.let { url ->
                    addLink(LinkAnnotation.Url(url), start, end)
                }
            }
        }
        matches.forEachIndexed { i, m ->
            val s = (m.first - block.charStart).coerceIn(0, text.length)
            val e = (m.last + 1 - block.charStart).coerceIn(0, text.length)
            if (e > s) {
                addStyle(
                    SpanStyle(
                        background = if (i == current) colors.primaryContainer else colors.secondaryContainer,
                    ),
                    s,
                    e,
                )
            }
        }
        for (h in state.highlightList) {
            val s = (h.start - block.charStart).coerceIn(0, text.length)
            val e = (h.end - block.charStart).coerceIn(0, text.length)
            if (e > s) {
                addStyle(SpanStyle(background = colors.tertiaryContainer), s, e)
            }
        }
        // 拖拽选区画在最上层（预选中，未落盘）。
        state.selectionRange?.let { r ->
            val s = (r.first - block.charStart).coerceIn(0, text.length)
            val e = (r.last + 1 - block.charStart).coerceIn(0, text.length)
            if (e > s) {
                addStyle(SpanStyle(background = colors.primaryContainer), s, e)
            }
        }
    }
}

/**
 * 富文本终端：统一用普通 Text，外链点击走 LinkAnnotation（外部浏览器打开，不经过 WebView）。
 * 样式对象由调用方按块类型构造，与旧分支逐参数写法等价。
 */
@Composable
private fun NovelRichText(
    block: NovelPageBlock,
    state: ReaderContract.State,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onHighlight: (() -> Unit)? = null,
    onHighlightWord: ((start: Int, end: Int) -> Unit)? = null,
    onHighlightRange: ((start: Int, end: Int) -> Unit)? = null,
    onHighlightRangeCancel: (() -> Unit)? = null,
) {
    val annotated = highlightMatches(block, state)
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    var dragAnchorPx by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var dragMoved by remember { mutableStateOf(false) }
    // 统一手势（仅划线模式）：长按起拖，松手无位移按圈词处理，有位移按区间处理。
    val dragSelect = if (onHighlightWord != null || onHighlightRange != null) {
        Modifier.pointerInput(onHighlightWord, onHighlightRange, onHighlightRangeCancel, block.text) {
            detectDragGesturesAfterLongPress(
                onDragStart = { dragAnchorPx = it; dragMoved = false },
                onDrag = { change, _ ->
                    change.consume()
                    dragMoved = true
                    layout?.let { result ->
                        val a = result.getOffsetForPosition(dragAnchorPx)
                        val b = result.getOffsetForPosition(change.position)
                        onHighlightRange?.invoke(a, b)
                    }
                },
                onDragEnd = {
                    if (!dragMoved) {
                        layout?.let { result ->
                            val word = core.model.wordRangeAt(block.text, result.getOffsetForPosition(dragAnchorPx))
                            if (!word.isEmpty()) onHighlightWord?.invoke(word.first, word.last + 1)
                        }
                    }
                },
                onDragCancel = { onHighlightRangeCancel?.invoke() },
            )
        }
    } else {
        Modifier
    }
    // 注意：if/else 与换行 .then() 的优先级陷阱——必须整体加括号，否则条件为真时修饰链丢失。
    val clickModifier = if (onHighlight != null) {
        // 长按由拖拽检测器接管（空实现仅用于屏蔽长按后的误触翻页/标记）；点按走切换。
        modifier.combinedClickable(onClick = onHighlight, onLongClick = { })
    } else {
        modifier
    }
    val finalModifier = clickModifier.then(dragSelect)
    Text(
        text = annotated,
        style = style,
        modifier = finalModifier,
        onTextLayout = { layout = it },
    )
}

/** 首帧占位：与正文同画布色，避免分页完成前白屏闪烁。 */
@Composable
private fun NovelPaginatingPlaceholder(theme: ReaderTheme) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxSize()
            .background(novelCanvasColor(theme)),
    )
}

private fun novelCanvasColor(theme: ReaderTheme): Color = when (theme) {
    ReaderTheme.BLACK -> Color(0xFF000000)
    ReaderTheme.DARK -> Color(0xFF15161A)
    ReaderTheme.WHITE -> Color(0xFFFAFAFA)
}

@Composable
private fun rememberComposeTextMeasure(): TextMeasure {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current.density
    return remember(measurer, density) { ComposeTextMeasure(measurer, density) }
}

private class ComposeTextMeasure(
    private val measurer: TextMeasurer,
    private val density: Float,
) : TextMeasure {
    override fun measure(text: String, style: NovelStyleSpec, maxWidthPx: Int): LineMetrics {
        if (text.isEmpty()) return LineMetrics(emptyList())
        val result = measurer.measure(
            text = text,
            style = TextStyle(
                fontSize = (style.fontSizePx / density).sp,
                lineHeight = (style.lineHeightPx / density).sp,
                fontWeight = if (style.kind == NovelBlockKind.HEADING) {
                    FontWeight.Bold
                } else {
                    FontWeight.Normal
                },
                fontStyle = if (style.kind == NovelBlockKind.QUOTE) {
                    FontStyle.Italic
                } else {
                    FontStyle.Normal
                },
            ),
            constraints = Constraints(maxWidth = maxWidthPx.coerceAtLeast(1)),
        )
        val lines = (0 until result.lineCount).mapNotNull { line ->
            val start = result.getLineStart(line).coerceIn(0, text.length)
            val end = result.getLineEnd(line).coerceIn(start, text.length)
            if (end <= start) {
                null
            } else {
                MeasuredLine(
                    start = start,
                    end = end,
                    heightPx = (result.getLineBottom(line) - result.getLineTop(line))
                        .coerceAtLeast(1f),
                )
            }
        }
        return LineMetrics(lines)
    }
}
