package feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 全局搜索（跨已启用源聚合，结果自带 [SearchWorkUi.sourceKey] 供按作品路由）。 */
@Serializable
data object SearchRoute : NavKey

/**
 * 搜索结果 UI 行（纯展示，由 App 层从源返回的 `SearchPage.works/comics` 映射）。
 * [sourceKey] 必须随行一路带到详情/阅读，否则多源下会回退到首个 enabled 源。
 */
data class SearchWorkUi(
    val workId: String,
    val sourceKey: String,
    val title: String,
    val subtitle: String = "",
    val cover: String? = null,
    val kindLabel: String = "manga",
    val sourceLabel: String = "",
)

/** 一次搜索的聚合结果：单源失败不拖垮全局，错误按源回传展示。 */
data class SearchPageUi(
    val works: List<SearchWorkUi> = emptyList(),
    val errors: List<String> = emptyList(),
    val maxPage: Int = 1,
)

/** MVI 契约（AGENTS.md 状态管理口径：Contract + ViewModel + StateFlow + Channel）。 */
object SearchContract {
    data class State(
        val query: String = "",
        val loading: Boolean = false,
        val page: Int = 1,
        val maxPage: Int = 1,
        val works: List<SearchWorkUi> = emptyList(),
        val errors: List<String> = emptyList(),
    )

    sealed interface Intent {
        data class Query(val value: String) : Intent
        data object Submit : Intent
        data object NextPage : Intent
        data class Open(val work: SearchWorkUi) : Intent
    }

    sealed interface Effect {
        data class OpenDetail(val work: SearchWorkUi) : Effect
    }
}

class SearchViewModel(
    private val loader: suspend (keyword: String, page: Int) -> SearchPageUi,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchContract.State())
    val state: StateFlow<SearchContract.State> = _state.asStateFlow()

    private val _effects = Channel<SearchContract.Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var loadJob: Job? = null

    fun onIntent(intent: SearchContract.Intent) {
        when (intent) {
            is SearchContract.Intent.Query -> _state.update { it.copy(query = intent.value) }
            SearchContract.Intent.Submit -> load(1)
            SearchContract.Intent.NextPage -> {
                val next = _state.value.page + 1
                if (!_state.value.loading && next <= _state.value.maxPage) load(next)
            }
            is SearchContract.Intent.Open -> _effects.trySend(SearchContract.Effect.OpenDetail(intent.work))
        }
    }

    private fun load(page: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, errors = emptyList()) }
            val result = runCatching { loader(_state.value.query, page) }
                .getOrElse { SearchPageUi(errors = listOf(it.message ?: "搜索失败")) }
            _state.update { s ->
                s.copy(
                    loading = false,
                    page = page,
                    maxPage = maxOf(result.maxPage, 1),
                    works = if (page > 1) s.works + result.works else result.works,
                    errors = result.errors,
                )
            }
        }
    }
}

/** Koin 接入前手写 Factory（与 ReaderViewModelFactory 同形）。 */
class SearchViewModelFactory(
    private val loader: suspend (String, Int) -> SearchPageUi,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: kotlin.reflect.KClass<T>,
        extras: CreationExtras,
    ): T {
        require(modelClass == SearchViewModel::class) { "仅支持 SearchViewModel" }
        return SearchViewModel(loader) as T
    }
}

@Composable
fun SearchScreen(
    onOpenDetail: (SearchWorkUi) -> Unit,
    loader: suspend (String, Int) -> SearchPageUi = { _, _ -> SearchPageUi() },
    /** 加入书架（null = 不展示入口；由 App 层接 ShelfBindings）。 */
    onAddToShelf: ((SearchWorkUi) -> Unit)? = null,
) {
    val vm: SearchViewModel = viewModel(
        key = "search",
        factory = SearchViewModelFactory(loader),
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is SearchContract.Effect.OpenDetail -> onOpenDetail(effect.work)
            }
        }
    }
    SearchContent(state = state, onIntent = vm::onIntent, onAddToShelf = onAddToShelf)
}

@Composable
fun SearchContent(
    state: SearchContract.State,
    onIntent: (SearchContract.Intent) -> Unit,
    onAddToShelf: ((SearchWorkUi) -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("搜索", style = MaterialTheme.typography.headlineSmall)
        Row(
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onIntent(SearchContract.Intent.Query(it)) },
                label = { Text("关键词（跨所有已启用源）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onIntent(SearchContract.Intent.Submit) },
                enabled = !state.loading && state.query.isNotBlank(),
            ) { Text("搜索") }
        }
        if (state.loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        for (error in state.errors) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        ) {
            items(state.works, key = { "${it.sourceKey}|${it.workId}" }) { work ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onIntent(SearchContract.Intent.Open(work)) },
                ) {
                    Column(Modifier.padding(Tokens.spacingM)) {
                        Text(work.title, style = MaterialTheme.typography.titleMedium)
                        if (work.subtitle.isNotBlank()) {
                            Text(work.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "${work.kindLabel} · ${work.sourceLabel.ifBlank { work.sourceKey }}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (onAddToShelf != null) {
                            TextButton(onClick = { onAddToShelf(work) }) { Text("加入书架") }
                        }
                    }
                }
            }
        }
        if (!state.loading && state.page < state.maxPage) {
            TextButton(onClick = { onIntent(SearchContract.Intent.NextPage) }) {
                Text("下一页（${state.page}/${state.maxPage}）")
            }
        }
        if (!state.loading && state.works.isEmpty() && state.errors.isEmpty() && state.query.isNotBlank()) {
            Text("无结果", style = MaterialTheme.typography.bodySmall)
        }
    }
}
