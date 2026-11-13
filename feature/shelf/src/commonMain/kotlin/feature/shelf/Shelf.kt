package feature.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import core.designsystem.Tokens
import core.model.LocalBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 书架（断网可用；在线收藏与本地书同构展示）。 */
@Serializable
data object ShelfRoute : NavKey

/**
 * 书架在线条目（UI 模型）。
 *
 * [workId] 是源内作品 id（详情/阅读路由用）；宿主稳定 id 只在数据层参与落库，
 * 不进入路由（禁止用 URL/宿主 id 反推源内 id）。
 */
data class ShelfWorkUi(
    val workId: String,
    val sourceKey: String,
    val title: String,
    val subtitle: String = "",
    val cover: String? = null,
    val kindLabel: String = "manga",
)

/** 本地书 UI 行（`local:` 前缀作品 id 直接作为路由参数）。 */
data class ShelfLocalBookUi(
    val workId: String,
    val title: String,
    val format: String,
)

/** 书架状态（单一 StateFlow 直出；首帧 loading，数据来自 DB 响应式流）。 */
@Immutable
data class ShelfState(
    val loading: Boolean = true,
    val works: List<ShelfWorkUi> = emptyList(),
    val localBooks: List<ShelfLocalBookUi> = emptyList(),
    val error: String? = null,
)

/**
 * 书架状态持有者（thin ViewModel：只有异步加载，无用户意图/一次性事件，
 * 按 `kmp-mvi` 分档不引入 Contract/Effect；导航回调留在 Screen 层）。
 */
class ShelfViewModel(
    onlineWorks: Flow<List<ShelfWorkUi>>,
    localBooks: Flow<List<LocalBook>>,
) : ViewModel() {

    val state: StateFlow<ShelfState> =
        combine(onlineWorks, localBooks) { works, books ->
            ShelfState(
                loading = false,
                works = works,
                localBooks = books.map { it.toUi() },
            )
        }
            .catch { emit(ShelfState(loading = false, error = it.message ?: "书架加载失败")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ShelfState(),
            )
}

private fun LocalBook.toUi(): ShelfLocalBookUi =
    ShelfLocalBookUi(workId = id, title = title.ifBlank { id }, format = format)

/** Koin 接入前手写 Factory（与 Search/Detail 同形）。 */
class ShelfViewModelFactory(
    private val onlineWorks: Flow<List<ShelfWorkUi>>,
    private val localBooks: Flow<List<LocalBook>>,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: kotlin.reflect.KClass<T>,
        extras: CreationExtras,
    ): T {
        require(modelClass == ShelfViewModel::class) { "仅支持 ShelfViewModel" }
        return ShelfViewModel(onlineWorks, localBooks) as T
    }
}

/** 本地书选择器（平台壳提供：桌面 FileDialog / Android SAF；iOS 暂缺，见导入矩阵）。 */
typealias PickLocalFiles = suspend () -> List<Pair<String, ByteArray>>

/** 封面字节加载器（App 层经源 HTTP 出口注入，白名单/Cookie/配对 UA 继承宿主实现）。 */
typealias ShelfCoverLoader = suspend (url: String) -> ByteArray?

@Composable
fun ShelfScreen(
    onOpenDetail: (workId: String, sourceKey: String) -> Unit,
    onlineWorks: Flow<List<ShelfWorkUi>> = flowOf(emptyList()),
    localBooks: Flow<List<LocalBook>> = flowOf(emptyList()),
    /** 打开本地书（workId 为 `local:` 前缀；调用方负责定位首章并导航）。 */
    onOpenLocalBook: (String) -> Unit = {},
    /** 删除本地书（目录 + 落库行；调用方负责刷新列表）。 */
    onDeleteLocalBook: (String) -> Unit = {},
    /** 选择器（平台壳注入；null = 本平台不支持导入，不展示入口）。 */
    pickLocalFiles: PickLocalFiles? = null,
    /** 导入回调（文件名 + 字节；null = 不展示入口）。 */
    onImportFiles: (suspend (List<Pair<String, ByteArray>>) -> Unit)? = null,
    /** 封面加载（null = 只显示标题占位，不发起网络）。 */
    coverLoader: ShelfCoverLoader? = null,
    /** 移出在线收藏（源内 workId + sourceKey；null = 不展示入口）。 */
    onRemoveFromShelf: ((String, String) -> Unit)? = null,
) {
    val vm: ShelfViewModel = viewModel(
        key = "shelf",
        factory = ShelfViewModelFactory(onlineWorks, localBooks),
    )
    val state by vm.state.collectAsState()
    ShelfContent(
        state = state,
        onOpenDetail = onOpenDetail,
        onOpenLocalBook = onOpenLocalBook,
        onDeleteLocalBook = onDeleteLocalBook,
        pickLocalFiles = pickLocalFiles,
        onImportFiles = onImportFiles,
        coverLoader = coverLoader,
        onRemoveFromShelf = onRemoveFromShelf,
    )
}

@Composable
fun ShelfContent(
    state: ShelfState,
    onOpenDetail: (workId: String, sourceKey: String) -> Unit,
    onOpenLocalBook: (String) -> Unit = {},
    onDeleteLocalBook: (String) -> Unit = {},
    pickLocalFiles: PickLocalFiles? = null,
    onImportFiles: (suspend (List<Pair<String, ByteArray>>) -> Unit)? = null,
    coverLoader: ShelfCoverLoader? = null,
    onRemoveFromShelf: ((String, String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val canImport = pickLocalFiles != null && onImportFiles != null
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("书架", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (canImport) {
                TextButton(onClick = {
                    scope.launch {
                        val files = runCatching { pickLocalFiles!!() }.getOrDefault(emptyList())
                        if (files.isNotEmpty()) onImportFiles!!(files)
                    }
                }) { Text("导入") }
            }
        }
        when {
            state.loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Text(
                state.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            state.works.isEmpty() && state.localBooks.isEmpty() -> Text(
                "书架为空",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        ) {
            if (state.works.isNotEmpty()) {
                item(key = "section-online") { SectionHeader("在线收藏") }
                items(state.works, key = { "w:${it.sourceKey}:${it.workId}" }) { work ->
                    ShelfWorkRow(
                        work = work,
                        coverLoader = coverLoader,
                        onClick = { onOpenDetail(work.workId, work.sourceKey) },
                        onRemove = onRemoveFromShelf?.let { callback ->
                            { callback(work.workId, work.sourceKey) }
                        },
                    )
                }
            }
            if (state.localBooks.isNotEmpty()) {
                item(key = "section-local") { SectionHeader("本地书") }
                items(state.localBooks, key = { "l:${it.workId}" }) { book ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(Tokens.spacingM),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
                        ) {
                            CoverThumb(url = null, title = book.title, loader = null)
                            Column(
                                Modifier.weight(1f).clickable { onOpenLocalBook(book.workId) },
                            ) {
                                Text(
                                    book.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "本地 · ${book.format}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(onClick = { onDeleteLocalBook(book.workId) }) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ShelfWorkRow(
    work: ShelfWorkUi,
    coverLoader: ShelfCoverLoader?,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(Tokens.spacingM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        ) {
            CoverThumb(url = work.cover, title = work.title, loader = coverLoader)
            Column(Modifier.weight(1f)) {
                Text(
                    work.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (work.subtitle.isNotBlank()) {
                    Text(
                        work.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${work.kindLabel} · ${work.sourceKey}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (onRemove != null) {
                TextButton(onClick = onRemove) { Text("移出") }
            }
        }
    }
}

/**
 * 列表封面：白色占位 + 标题首字；有 loader 且 URL 非空才走网络。
 * `produceState` 以 URL 为 key，LazyColumn 复用行时不会重复请求已换行的旧图。
 */
@Composable
private fun CoverThumb(
    url: String?,
    title: String,
    loader: ShelfCoverLoader?,
) {
    val bytes by produceState<ByteArray?>(initialValue = null, key1 = url) {
        val current = url
        if (current.isNullOrBlank() || loader == null) {
            value = null
            return@produceState
        }
        value = runCatching { loader(current) }.getOrNull()
    }
    Box(
        modifier = Modifier
            .size(width = Tokens.coverWidth, height = Tokens.coverHeight)
            .clip(RoundedCornerShape(Tokens.spacingS))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val data = bytes
        if (data != null) {
            AsyncImage(
                model = data,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                title.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
