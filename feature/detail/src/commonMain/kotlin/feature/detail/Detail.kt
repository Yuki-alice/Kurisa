package feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import core.designsystem.Tokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 作品详情（章节列表虚拟化、更新角标、下载粒度随 M5）。
 *
 * [sourceKey] 是作品归属源（B4），透传给 `ReaderRoute`，避免多源时回退首个启用源；
 * [title] 用于顶栏，未知时由详情请求补齐。
 */
@Serializable
data class DetailRoute(
    val workId: String,
    val sourceKey: String = "",
    val title: String = "",
) : NavKey

/** 详情页数据（本地/在线共用；由 composition root 装载，feature 不依赖源实现）。 */
data class DetailData(
    val title: String,
    val description: String = "",
    val chapters: List<DetailChapterUi> = emptyList(),
    /** 副标题（作者/话数/来源），展示在标题下方。 */
    val meta: String = "",
    /** 封面原始字节；null 时展示首字占位。 */
    val coverBytes: ByteArray? = null,
)

data class DetailChapterUi(val id: String, val title: String)

data class DetailUiState(
    val title: String = "",
    val description: String = "",
    val chapters: List<DetailChapterUi> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val meta: String = "",
    val coverBytes: ByteArray? = null,
)

/**
 * 详情 ViewModel（MVI 同形：StateFlow 状态 + 一次性 Intent）。
 *
 * 装载器由 composition root 注入：本地作品走 `ChapterProvider.chapters`，
 * 在线作品走源 `loadInfo`（章节同源），页面本身不认识 Data 层。
 */
class DetailViewModel(
    private val titleHint: String,
    private val load: suspend () -> DetailData,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState(title = titleHint))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { load() }
                .onSuccess { data ->
                    _state.value = DetailUiState(
                        title = data.title.ifBlank { titleHint },
                        description = data.description,
                        chapters = data.chapters,
                        loading = false,
                        meta = data.meta,
                        coverBytes = data.coverBytes,
                    )
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "加载失败")
                    }
                }
        }
    }
}

class DetailViewModelFactory(
    private val titleHint: String,
    private val load: suspend () -> DetailData,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: kotlin.reflect.KClass<T>,
        extras: androidx.lifecycle.viewmodel.CreationExtras,
    ): T {
        require(modelClass == DetailViewModel::class) { "仅支持 DetailViewModel" }
        return DetailViewModel(titleHint, load) as T
    }
}

@Composable
fun DetailScreen(
    workId: String,
    onOpenReader: (workId: String, chapterId: String, title: String) -> Unit,
    onBack: () -> Unit,
    sourceKey: String = "",
    title: String = "",
    load: suspend () -> DetailData,
) {
    // 重组时闭包可能更新；VM 只构造一次，用 rememberUpdatedState 让最新闭包生效。
    val currentLoad by rememberUpdatedState(load)
    val vm: DetailViewModel = viewModel(
        key = "detail:$workId:$sourceKey",
        factory = DetailViewModelFactory(title) { currentLoad() },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    DetailContent(
        state = state,
        sourceKey = sourceKey,
        onRetry = vm::refresh,
        onOpenChapter = { chapter -> onOpenReader(workId, chapter.id, chapter.title) },
        onBack = onBack,
    )
}


/** 纯展示（截图测试直接喂 State，不碰 VM 与网络）。 */
@Composable
fun DetailContent(
    state: DetailUiState,
    sourceKey: String,
    onRetry: () -> Unit,
    onOpenChapter: (DetailChapterUi) -> Unit,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 限宽：详情是「一列纵向信息」，宽屏通栏会把标题和封面拉得极远。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 820.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            DetailTopBar(title = state.title, onBack = onBack)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Tokens.spacingL,
                    end = Tokens.spacingL,
                    bottom = Tokens.spacingXl,
                ),
            ) {
                item {
                    DetailHero(
                        state = state,
                        sourceKey = sourceKey,
                        onRetry = onRetry,
                        onOpenChapter = onOpenChapter,
                    )
                }
                if (state.chapters.isNotEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = Tokens.spacingL, bottom = Tokens.spacingM),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "目录",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${state.chapters.size} 话",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                    itemsIndexed(state.chapters, key = { _, c -> c.id }) { index, chapter ->
                        ChapterRow(index = index, chapter = chapter, onClick = { onOpenChapter(chapter) })
                    }
                } else if (!state.loading && state.error == null) {
                    item {
                        Text(
                            "暂无章节",
                            Modifier.padding(vertical = Tokens.spacingXl),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.spacingM, vertical = Tokens.spacingM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("‹ 书架") }
        Text(
            title.ifBlank { "作品详情" },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 头部：封面 + 标题 + 元信息 + 主操作（首话直达，避免用户还要在目录里找第 1 话）。 */
@Composable
private fun DetailHero(
    state: DetailUiState,
    sourceKey: String,
    onRetry: () -> Unit,
    onOpenChapter: (DetailChapterUi) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = Tokens.spacingM)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacingL)) {
            Box(
                Modifier.width(124.dp)
                    .aspectRatio(3f / 4f)
                    .shadow(6.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val cover = state.coverBytes
                if (cover != null && cover.isNotEmpty()) {
                    AsyncImage(
                        model = cover,
                        contentDescription = state.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                            ),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            state.title.take(1).ifBlank { "书" },
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.title.ifBlank { "加载中…" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.meta.isNotBlank()) {
                    Spacer(Modifier.height(Tokens.spacingS))
                    Text(
                        state.meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sourceKey.isNotBlank()) {
                    Spacer(Modifier.height(Tokens.spacingS))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            "来源 $sourceKey",
                            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (state.loading) {
                    Spacer(Modifier.height(Tokens.spacingM))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  加载中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.chapters.firstOrNull()?.let { first ->
                    Spacer(Modifier.height(Tokens.spacingL))
                    Button(onClick = { onOpenChapter(first) }) { Text("开始阅读") }
                }
            }
        }
        if (state.description.isNotBlank()) {
            Spacer(Modifier.height(Tokens.spacingL))
            Text(
                state.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.error?.let { err ->
            Spacer(Modifier.height(Tokens.spacingM))
            Text("加载失败：$err", color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun ChapterRow(index: Int, chapter: DetailChapterUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.width(24.dp),
        )
        Text(
            chapter.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
