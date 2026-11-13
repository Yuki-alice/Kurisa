package feature.shelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
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

data class ShelfUiState(
    val loading: Boolean = false,
    val workIds: List<String> = emptyList(),
)

/** 状态持有者（M5 按 state-management/mvi 二选一收敛；此处仅 StateFlow 直出）。 */
class ShelfStateHolder {
    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()
}

/** 本地书选择器（平台壳提供：桌面 FileDialog / Android SAF；iOS 暂缺，见导入矩阵）。 */
typealias PickLocalFiles = suspend () -> List<Pair<String, ByteArray>>

@Composable
fun ShelfScreen(
    onOpenDetail: (String) -> Unit,
    /** 本地书（与在线书同构展示；App 层装配后传入，默认空即无此分区）。 */
    localBooks: List<LocalBook> = emptyList(),
    /** 打开本地书（workId 为 `local:` 前缀；调用方负责定位首章并导航）。 */
    onOpenLocalBook: (String) -> Unit = {},
    /** 删除本地书（目录 + 落库行；调用方负责刷新列表）。 */
    onDeleteLocalBook: (String) -> Unit = {},
    /** 选择器（平台壳注入；null = 本平台不支持导入，不展示入口）。 */
    pickLocalFiles: PickLocalFiles? = null,
    /** 导入回调（文件名 + 字节；null = 不展示入口）。 */
    onImportFiles: (suspend (List<Pair<String, ByteArray>>) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val canImport = pickLocalFiles != null && onImportFiles != null
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("书架")
        // TODO(M5): 接 ShelfRepository.observeShelf + 分页 + 封面；文案进本地化。
        Button(onClick = { onOpenDetail("demo-work") }) { Text("打开详情") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "本地书",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (canImport) {
                TextButton(onClick = {
                    scope.launch {
                        val files = runCatching { pickLocalFiles!!() }.getOrDefault(emptyList())
                        if (files.isNotEmpty()) onImportFiles!!(files)
                    }
                }) { Text("导入") }
            }
        }
        if (localBooks.isEmpty()) {
            Text(
                "暂无本地书",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
            ) {
                items(localBooks, key = { it.id }) { book ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenLocalBook(book.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onDeleteLocalBook(book.id) }) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}
