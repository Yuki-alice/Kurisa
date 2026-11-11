package feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import core.reader.ReadingMode
import data.repository.ProgressRepository
import kotlinx.serialization.Serializable

/** 阅读器（漫画 & 小说共用外壳，内部分支；单页渲染器随 Slice B 接 ZoomImage）。 */
@Serializable
data class ReaderRoute(val workId: String, val chapterId: String) : NavKey

@Composable
fun ReaderScreen(
    workId: String,
    chapterId: String,
    onBack: () -> Unit,
    chapters: core.reader.ChapterProvider = DemoChapterProvider,
    progress: ProgressRepository = NoopProgressRepository,
) {
    val vm: ReaderViewModel = viewModel(
        key = "reader:$workId:$chapterId",
        factory = ReaderViewModelFactory(
            workId = workId,
            chapterId = chapterId,
            chapters = chapters,
            saveProgress = SaveReadingProgressUseCase(progress),
            loadProgress = LoadReadingProgressUseCase(progress),
        ),
    )
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
        // Slice B 替换为 ZoomImage 单页渲染器；此处占位保证翻页/进度链路可测。
        PagePlaceholder(index = state.pageIndex, total = state.pageCount, modifier = Modifier.weight(1f))
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
    // 可见首项变化即翻页（进度照常防抖持久化）。
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { onIntent(ReaderContract.Intent.PageChanged(it)) }
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
        itemsIndexed(List(state.pageCount.coerceAtLeast(1)) { it }) { index, _ ->
            PagePlaceholder(index = index, total = state.pageCount)
        }
    }
}

@Composable
private fun PagePlaceholder(index: Int, total: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().aspectRatio(Tokens.coverAspect),
        tonalElevation = Tokens.spacingS,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("P${index + 1} / $total")
        }
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
