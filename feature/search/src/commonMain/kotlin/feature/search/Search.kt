package feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
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

/** 全局搜索（跨源聚合、去重、筛选随 M5）。 */
@Serializable
data object SearchRoute : NavKey

/** 可搜索源（App 层从 registry 取启用源；feature 不依赖源实现）。 */
data class SearchSourceUi(val key: String, val name: String)

data class SearchResultUi(
    val workId: String,
    val title: String,
    val subtitle: String = "",
    /** 封面原始字节；null 时展示首字占位（封面缺失不影响能否点进去）。 */
    val coverBytes: ByteArray? = null,
)

/** 一页搜索结果（sourceKey 由调用方回填，条目本身不含来源）。 */
data class SearchPageUi(
    val works: List<SearchResultUi> = emptyList(),
    val maxPage: Int = 1,
)

data class SearchUiState(
    val query: String = "",
    val sourceKey: String = "",
    val loading: Boolean = false,
    val results: List<SearchResultUi> = emptyList(),
    val page: Int = 1,
    val maxPage: Int = 1,
    val searched: Boolean = false,
    val error: String? = null,
)

/**
 * 搜索 ViewModel（MVI：StateFlow 状态 + Intent 方法）。
 *
 * 搜索能力由 composition root 注入（源 key → 源调用），页面不依赖 bridge；
 * 失败按 `error` 呈现并可重试，不留白屏（C4）。
 */
class SearchViewModel(
    sources: List<SearchSourceUi>,
    private val search: suspend (sourceKey: String, keyword: String, page: Int) -> SearchPageUi,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SearchUiState(sourceKey = sources.firstOrNull()?.key.orEmpty()),
    )
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    val sources: List<SearchSourceUi> = sources

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun selectSource(key: String) = _state.update { it.copy(sourceKey = key) }

    fun submit() {
        val s = _state.value
        if (s.query.isBlank() || s.sourceKey.isBlank()) return
        load(page = 1)
    }

    fun nextPage() {
        val s = _state.value
        if (s.page < s.maxPage) load(page = s.page + 1)
    }

    fun prevPage() {
        val s = _state.value
        if (s.page > 1) load(page = s.page - 1)
    }

    private fun load(page: Int) {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { search(s.sourceKey, s.query.trim(), page) }
                .onSuccess { p ->
                    _state.update {
                        it.copy(
                            loading = false,
                            searched = true,
                            results = p.works,
                            page = page,
                            maxPage = p.maxPage,
                            error = null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "搜索失败")
                    }
                }
        }
    }
}

class SearchViewModelFactory(
    private val sources: List<SearchSourceUi>,
    private val search: suspend (String, String, Int) -> SearchPageUi,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: kotlin.reflect.KClass<T>,
        extras: androidx.lifecycle.viewmodel.CreationExtras,
    ): T {
        require(modelClass == SearchViewModel::class) { "仅支持 SearchViewModel" }
        return SearchViewModel(sources, search) as T
    }
}

@Composable
fun SearchScreen(
    sources: List<SearchSourceUi> = emptyList(),
    onOpenDetail: (workId: String, sourceKey: String, title: String) -> Unit = { _, _, _ -> },
    search: suspend (sourceKey: String, keyword: String, page: Int) -> SearchPageUi = { _, _, _ ->
        SearchPageUi()
    },
) {
    val currentSearch by rememberUpdatedState(search)
    val vm: SearchViewModel = viewModel(
        key = "search",
        factory = SearchViewModelFactory(sources) { k, kw, p -> currentSearch(k, kw, p) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    SearchContent(
        state = state,
        sources = vm.sources,
        onQueryChange = vm::onQueryChange,
        onSubmit = vm::submit,
        onSelectSource = vm::selectSource,
        onPrev = vm::prevPage,
        onNext = vm::nextPage,
        onOpenWork = { work ->
            onOpenDetail(work.workId, state.sourceKey, work.title)
        },
    )
}

/** 纯展示（截图测试直接喂 State）。 */
@Composable
fun SearchContent(
    state: SearchUiState,
    sources: List<SearchSourceUi>,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelectSource: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenWork: (SearchResultUi) -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 820.dp).fillMaxHeight().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("搜索", style = MaterialTheme.typography.headlineSmall)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("关键词") },
            )
            Button(onClick = onSubmit, enabled = !state.loading) { Text("搜索") }
        }
        if (sources.isNotEmpty()) {
            Text("来源：", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacingS)) {
                sources.forEach { s ->
                    TextButton(onClick = { onSelectSource(s.key) }) {
                        Text(
                            if (s.key == state.sourceKey) "● ${s.name}" else "○ ${s.name}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).then(Modifier))
                Text("搜索中…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.error?.let { Text("搜索失败：$it", color = MaterialTheme.colorScheme.error) }
        if (state.searched && !state.loading && state.results.isEmpty() && state.error == null) {
            Text("没有结果", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
            items(state.results, key = { it.workId }) { work ->
                SearchResultRow(work = work, onClick = { onOpenWork(work) })
            }
        }
        if (state.searched) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPrev, enabled = state.page > 1) { Text("上一页") }
                Text("${state.page} / ${state.maxPage}", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onNext, enabled = state.page < state.maxPage) { Text("下一页") }
            }
        }
    }
    }
}

/** 结果行：封面缩略图 + 标题/副标题。漫画靠封面辨认，纯文字列表读不出是哪本。 */
@Composable
private fun SearchResultRow(work: SearchResultUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(Tokens.spacingS),
        horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 76.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val cover = work.coverBytes
            if (cover != null && cover.isNotEmpty()) {
                AsyncImage(
                    model = cover,
                    contentDescription = work.title,
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
                        work.title.take(1).ifBlank { "书" },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                work.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (work.subtitle.isNotBlank()) {
                Text(
                    work.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
