package feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.github.panpf.zoomimage.CoilZoomAsyncImage
import core.designsystem.Tokens
import core.reader.ChapterProvider
import core.reader.ReadingMode
import data.images.SourceImageFetcher
import data.network.CookieStore
import data.network.SourceHttpClient
import data.network.defaultHttpClient
import data.repository.ProgressRepository
import kotlinx.serialization.Serializable

/** 阅读器（漫画 & 小说共用外壳，内部分支；单页 ZoomImage + 长条 LazyColumn）。 */
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
) {
    // 网络栈装配（Koin M5 前手写：CookieStore 按 composition 记忆；
    // 会话级共享随 M5 DI 落地；demo 源无鉴权需求）。
    val cookies = remember { CookieStore() }
    val images = remember {
        SourceImageFetcher(SourceHttpClient(defaultHttpClient(), cookies) { 0L })
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
    Scaffold(snackbarHost = { SnackbarHost(snacks) }) { padding ->
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
    Column(modifier.fillMaxSize().padding(Tokens.spacingL)) {
        ReaderTopBar(
            title = state.chapterTitle.ifEmpty { state.chapterId.ifEmpty { "阅读" } },
            mode = state.mode,
            onModeChange = { onIntent(ReaderContract.Intent.ModeChanged(it)) },
            onBack = onBack,
        )
        when {
            state.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
            state.error != null -> Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("加载失败：${state.error}")
                Button(onClick = { onIntent(ReaderContract.Intent.RetryClicked) }) { Text("重试") }
            }
            state.article != null -> NovelContent(state.article, Modifier.weight(1f))
            else -> when (state.mode) {
                ReadingMode.PAGED -> PagedContent(state, onIntent, Modifier.weight(1f))
                ReadingMode.CONTINUOUS -> ContinuousContent(state, onIntent, Modifier.weight(1f))
            }
        }
        ReaderBottomBar(state, onIntent)
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    mode: ReadingMode,
    onModeChange: (ReadingMode) -> Unit,
    onBack: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(title, style = MaterialTheme.typography.titleMedium)
        TextButton(
            onClick = {
                onModeChange(if (mode == ReadingMode.PAGED) ReadingMode.CONTINUOUS else ReadingMode.PAGED)
            },
        ) { Text(if (mode == ReadingMode.PAGED) "长条" else "单页") }
    }
}

@Composable
private fun PagedContent(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
        PageImage(
            bytes = state.pages[state.pageIndex],
            desc = "P${state.pageIndex + 1}",
            contentScale = ContentScale.Fit,
            onRetry = { onIntent(ReaderContract.Intent.RefreshPage) },
            modifier = Modifier.weight(1f),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = { onIntent(ReaderContract.Intent.PrevPage) }) { Text("上一页") }
            Text("${state.pageIndex + 1} / ${state.pageCount}")
            Button(onClick = { onIntent(ReaderContract.Intent.NextPage) }) { Text("下一页") }
        }
    }
}

@Composable
private fun ContinuousContent(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    // 可见首项变化即翻页（进度照常防抖持久化；窗口跟随首项）。
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { onIntent(ReaderContract.Intent.PageChanged(it)) }
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
        itemsIndexed(List(state.pageCount.coerceAtLeast(1)) { it }) { index, _ ->
            // LazyColumn 纵轴无约束：必须给确定高宽比，否则异步图加载前高度塌成 0
            // （M3 真机实录；长条真实宽高比随 M-hardening 用解码尺寸替换）。
            PageImage(
                bytes = state.pages[index],
                desc = "P${index + 1}",
                contentScale = ContentScale.FillWidth,
                onRetry = { onIntent(ReaderContract.Intent.RefreshPage) },
                modifier = Modifier.aspectRatio(Tokens.coverAspect),
            )
        }
    }
}

/**
 * 单页图：有字节走 CoilZoomAsyncImage（Coil 解码 + ZoomImage 缩放/分块），
 * 无字节（加载中/失败）走占位，点击重试。
 * 手势说明：单页无横滑竞争（按钮翻页）；长条 LazyColumn 与捏合的嵌套滚动
 * 协调待真机压测（M3-hardening，见 ADR-007）。
 */
@Composable
private fun PageImage(
    bytes: ByteArray?,
    desc: String,
    contentScale: ContentScale,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bytes == null) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(Tokens.coverAspect)
                .clickable(onClick = onRetry),
            tonalElevation = Tokens.spacingS,
            shape = MaterialTheme.shapes.medium,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("加载中，点击重试")
            }
        }
    } else {
        CoilZoomAsyncImage(
            model = bytes,
            contentDescription = desc,
            modifier = modifier.fillMaxWidth(),
            contentScale = contentScale,
        )
    }
}

@Composable
private fun NovelContent(article: String, modifier: Modifier = Modifier) {
    // M4 做排版分页；此处受限标签已由宿主清洗（HtmlAllowlist），纯文本直排。
    val scroll = androidx.compose.foundation.rememberScrollState()
    Column(modifier.verticalScroll(scroll)) {
        Text(article)
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderContract.State,
    onIntent: (ReaderContract.Intent) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onIntent(ReaderContract.Intent.PrevChapter) }) { Text("上一章") }
        Text("${state.chapterIndex + 1} / ${state.chapterCount}")
        TextButton(onClick = { onIntent(ReaderContract.Intent.NextChapter) }) { Text("下一章") }
    }
}
