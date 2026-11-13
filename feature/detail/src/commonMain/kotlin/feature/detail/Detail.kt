package feature.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * 作品详情路由。[sourceKey] 由搜索/发现页随行传入，App 层据此绑定按作品路由；
 * 本地书没有源归属，保持空串走 `local:` 前缀链路。
 */
@Serializable
data class DetailRoute(
    val workId: String,
    val sourceKey: String = "",
    val title: String = "",
) : NavKey

/** 章节 UI 行（由 App 层从 `WorkDetails.chapters` 三形状归一化）。 */
data class ChapterUiItem(
    val id: String,
    val title: String,
    val ordinal: Int,
)

/** 详情 UI 行；[error] 非空表示加载失败，UI 显示错误与重试，不伪装成空目录。 */
data class WorkDetailUi(
    val workId: String,
    val sourceKey: String,
    val title: String,
    val chapters: List<ChapterUiItem> = emptyList(),
    val error: String? = null,
)

object DetailContract {
    data class State(
        val workId: String = "",
        val sourceKey: String = "",
        val title: String = "",
        val loading: Boolean = false,
        val chapters: List<ChapterUiItem> = emptyList(),
        val error: String? = null,
    )

    sealed interface Intent {
        data object Reload : Intent
        data class OpenChapter(val chapterId: String) : Intent
    }

    sealed interface Effect {
        data class OpenReader(val workId: String, val chapterId: String) : Effect
    }
}

class DetailViewModel(
    private val workId: String,
    private val sourceKey: String,
    initialTitle: String,
    private val loader: suspend (workId: String, sourceKey: String) -> WorkDetailUi,
) : ViewModel() {
    private val _state = MutableStateFlow(
        DetailContract.State(workId = workId, sourceKey = sourceKey, title = initialTitle),
    )
    val state: StateFlow<DetailContract.State> = _state.asStateFlow()

    private val _effects = Channel<DetailContract.Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        load()
    }

    fun onIntent(intent: DetailContract.Intent) {
        when (intent) {
            DetailContract.Intent.Reload -> load()
            is DetailContract.Intent.OpenChapter ->
                _effects.trySend(DetailContract.Effect.OpenReader(workId, intent.chapterId))
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = runCatching { loader(workId, sourceKey) }
                .getOrElse { WorkDetailUi(workId, sourceKey, workId, error = it.message ?: "详情加载失败") }
            _state.update {
                it.copy(
                    loading = false,
                    title = result.title.ifBlank { workId },
                    chapters = result.chapters,
                    error = result.error,
                )
            }
        }
    }
}

/** Koin 接入前手写 Factory（与 ReaderViewModelFactory 同形）。 */
class DetailViewModelFactory(
    private val workId: String,
    private val sourceKey: String,
    private val initialTitle: String,
    private val loader: suspend (String, String) -> WorkDetailUi,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: kotlin.reflect.KClass<T>,
        extras: CreationExtras,
    ): T {
        require(modelClass == DetailViewModel::class) { "仅支持 DetailViewModel" }
        return DetailViewModel(workId, sourceKey, initialTitle, loader) as T
    }
}

@Composable
fun DetailScreen(
    workId: String,
    sourceKey: String = "",
    title: String = "",
    onOpenReader: (workId: String, chapterId: String) -> Unit,
    onBack: () -> Unit,
    loader: suspend (String, String) -> WorkDetailUi =
        { w, k -> WorkDetailUi(w, k, w, error = "未装配") },
) {
    val vm: DetailViewModel = viewModel(
        key = "detail:$sourceKey:$workId",
        factory = DetailViewModelFactory(workId, sourceKey, title, loader),
    )
    val state by vm.state.collectAsState()
    LaunchedEffect(vm) {
        vm.effects.collect { effect ->
            when (effect) {
                is DetailContract.Effect.OpenReader -> onOpenReader(effect.workId, effect.chapterId)
            }
        }
    }
    DetailContent(state = state, onIntent = vm::onIntent, onBack = onBack)
}

@Composable
fun DetailContent(
    state: DetailContract.State,
    onIntent: (DetailContract.Intent) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text(state.title.ifBlank { state.workId }, style = MaterialTheme.typography.headlineSmall)
        Text(
            if (state.sourceKey.isBlank()) "本地书" else state.sourceKey,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
            Button(onClick = onBack) { Text("返回") }
            Button(onClick = { onIntent(DetailContract.Intent.Reload) }) { Text("刷新") }
        }
        if (state.loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        ) {
            itemsIndexed(state.chapters, key = { _, c -> c.id }) { index, chapter ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onIntent(DetailContract.Intent.OpenChapter(chapter.id)) },
                ) {
                    Column(Modifier.padding(Tokens.spacingM)) {
                        Text(chapter.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "第 ${chapter.ordinal.ifZero(index + 1)} 章 · ${chapter.id}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        if (!state.loading && state.error == null && state.chapters.isEmpty()) {
            Text("暂无章节", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun Int.ifZero(fallback: Int): Int = if (this == 0) fallback else this
