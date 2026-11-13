package feature.shelf

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import core.designsystem.Tokens
import core.model.LocalBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** 书架（断网可用，见 M2；万级条目分页 + 封面懒加载随 M5）。 */
@Serializable
data object ShelfRoute : NavKey

/**
 * 书架条目（纯展示模型）。
 *
 * [workId] 是路由键：本地作品为 `local:<目录名>`，在线作品为 sha256 稳定 id；
 * [sourceKey] 透传给详情/阅读器（B4），空串表示由 provider 回退。
 * [coverBytes] 为封面原始字节（本地 = 首张图片，在线 = 站点封面）；
 * 为 null 时用标题首字渐变占位，不做网络回退（断网书架也要能看）。
 */
class ShelfWorkUi(
    val workId: String,
    val title: String,
    val subtitle: String = "",
    val sourceKey: String = "",
    val badge: String = "本地",
    val coverBytes: ByteArray? = null,
)

data class ShelfUiState(
    val loading: Boolean = false,
    val works: List<ShelfWorkUi> = emptyList(),
)

/** 状态持有者（书架数据由 composition root 注入；分页/仓储随 M5）。 */
class ShelfStateHolder {
    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    fun submit(works: List<ShelfWorkUi>, loading: Boolean = false) {
        _state.value = ShelfUiState(loading = loading, works = works)
    }
}

/**
 * 本地书选择器（平台壳提供：桌面 FileDialog / Android SAF；iOS 暂缺，见导入矩阵）。
 *
 * 与「选目录」是两条入口：选目录直接扫盘（有封面、无需入库），
 * [PickLocalFiles] 是按文件导入（落库、可删除）。
 */
typealias PickLocalFiles = suspend () -> List<Pair<String, ByteArray>>

/**
 * 书架页（受控组件：数据与动作由 composition root 注入，页面不持有仓储）。
 *
 * 网格 + 真实封面：漫画是视觉品类，列表行读不出「这是哪本」。
 * 封面取本地库首张图片（CBZ 首页 / 目录首图 / cover.*），读不到才退回首字色块。
 *
 * 两种本地来源并存，优先展示有封面的目录扫描结果；未选目录时才列已入库的
 * [localBooks]（导入路径，暂无封面）。两套入口待 M5 统一（见 docs 记录的偏差）。
 */
@Composable
fun ShelfScreen(
    works: List<ShelfWorkUi> = emptyList(),
    localRootLabel: String? = null,
    picking: Boolean = false,
    onPickDirectory: (() -> Unit)? = null,
    onOpenWork: (ShelfWorkUi) -> Unit = {},
    onOpenSearch: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = null,
    /** 已入库的本地书（与在线书同构展示；App 层装配后传入）。 */
    localBooks: List<LocalBook> = emptyList(),
    /** 打开已入库的本地书（workId 为 `local:` 前缀）。 */
    onOpenLocalBook: (String) -> Unit = {},
    /** 删除已入库的本地书（目录 + 落库行；调用方负责刷新列表）。 */
    onDeleteLocalBook: (String) -> Unit = {},
    /** 按文件导入的选择器（null = 本平台不支持，不展示入口）。 */
    pickLocalFiles: PickLocalFiles? = null,
    /** 导入回调（文件名 + 字节；null = 不展示入口）。 */
    onImportFiles: (suspend (List<Pair<String, ByteArray>>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val canImport = pickLocalFiles != null && onImportFiles != null
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // 桌面宽屏下内容限宽居中：四本书铺满 2000px 会显得封面巨大且难扫视。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 1180.dp).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ShelfHeader(
                    works = works,
                    localRootLabel = localRootLabel,
                    picking = picking,
                    onPickDirectory = onPickDirectory,
                    onOpenSearch = onOpenSearch,
                    onOpenSources = onOpenSources,
                    canImport = canImport,
                    onImport = {
                        if (canImport) {
                            scope.launch {
                                val files = runCatching { pickLocalFiles!!() }.getOrDefault(emptyList())
                                if (files.isNotEmpty()) onImportFiles!!(files)
                            }
                        }
                    },
                )
                when {
                    works.isNotEmpty() -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 168.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.spacingL),
                        contentPadding = PaddingValues(
                            top = Tokens.spacingM,
                            bottom = Tokens.spacingXl,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Tokens.spacingL),
                        verticalArrangement = Arrangement.spacedBy(Tokens.spacingXl),
                    ) {
                        items(works, key = { it.workId }) { work ->
                            WorkCard(work, onClick = { onOpenWork(work) })
                        }
                    }

                    localBooks.isNotEmpty() -> ImportedBookList(
                        books = localBooks,
                        onOpen = onOpenLocalBook,
                        onDelete = onDeleteLocalBook,
                    )

                    else -> EmptyShelf(picking = picking)
                }
            }
        }
    }
}

@Composable
private fun ShelfHeader(
    works: List<ShelfWorkUi>,
    localRootLabel: String?,
    picking: Boolean,
    onPickDirectory: (() -> Unit)?,
    onOpenSearch: (() -> Unit)?,
    onOpenSources: (() -> Unit)?,
    canImport: Boolean,
    onImport: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(Tokens.spacingL)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("书架", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(if (works.isEmpty()) "暂无作品" else "${works.size} 部作品")
                        localRootLabel?.let { append(" · 本地库 $it") } ?: append(" · 未选择本地库")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
                onOpenSearch?.let { OutlinedButton(onClick = it) { Text("搜索") } }
                onOpenSources?.let { OutlinedButton(onClick = it) { Text("管理源") } }
                if (canImport) {
                    OutlinedButton(onClick = onImport) { Text("导入") }
                }
                onPickDirectory?.let {
                    FilledTonalButton(onClick = it, enabled = !picking) {
                        if (picking) {
                            CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(0.dp))
                        }
                        Text(if (picking) " 扫描中…" else "选择本地目录")
                    }
                }
            }
        }
    }
}

/** 导入库列表（暂无封面）：保留删除入口，点行即读首章。 */
@Composable
private fun ImportedBookList(
    books: List<LocalBook>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingS),
    ) {
        items(books, key = { it.id }) { book ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpen(book.id) }
                    .padding(vertical = Tokens.spacingS, horizontal = Tokens.spacingM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDelete(book.id) }) { Text("删除") }
            }
        }
    }
}

/** 统一封面比例 3:4（漫画单行本近似），避免网格高度参差。 */
@Composable
private fun WorkCard(work: ShelfWorkUi, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
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
                CoverPlaceholder(work.title)
            }
            if (work.badge.isNotBlank()) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    Text(
                        work.badge,
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
        Spacer(Modifier.height(Tokens.spacingM))
        // 文本块整体定高、副标题贴底：标题 1 行/2 行混排时，网格行高与副标题才能对齐。
        Column(Modifier.height(64.dp)) {
            Text(
                work.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
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

/** 无封面占位：按标题哈希选渐变色，中央放首字（确定性，无随机）。 */
@Composable
private fun CoverPlaceholder(title: String) {
    val palettes = listOf(
        listOf(Color(0xFF6D5BD0), Color(0xFF9C8CF0)),
        listOf(Color(0xFF2E7D6B), Color(0xFF63B5A0)),
        listOf(Color(0xFF9C4B2E), Color(0xFFD68A62)),
        listOf(Color(0xFF37527A), Color(0xFF6C8CBE)),
    )
    val palette = palettes[title.hashCode().let { if (it < 0) -it else it } % palettes.size]
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(palette)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.take(1).ifBlank { "书" },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}

@Composable
private fun EmptyShelf(picking: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingXl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (picking) "正在扫描本地库…" else "书架还是空的",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(Tokens.spacingM))
        Text(
            "选择一个目录，里面每个子文件夹（或 .cbz 文件）就是一部作品。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
