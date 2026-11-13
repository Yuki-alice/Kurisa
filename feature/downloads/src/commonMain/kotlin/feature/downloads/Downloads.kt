package feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

/** 下载队列（`:data:download` 编排；表现层视觉还原待设计定稿，本轮只做数据接线）。 */
@Serializable
data object DownloadsRoute : NavKey

/**
 * 下载任务展示行。
 *
 * feature 层不依赖 `:data:download` / `:core:model` 的领域枚举，
 * 由 App 层把 `DownloadTask` 映射成本行（Screen/Content 分离口径）。
 */
data class DownloadTaskUi(
    val id: String,
    val title: String,
    val statusLabel: String,
    val done: Long,
    val total: Long,
    val error: String?,
    val canPause: Boolean,
    val canResume: Boolean,
)

@Composable
fun DownloadsScreen(
    tasks: Flow<List<DownloadTaskUi>> = flowOf(emptyList()),
    onPause: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
) {
    val rows by tasks.collectAsState(initial = emptyList())
    DownloadsContent(
        tasks = rows,
        onPause = onPause,
        onResume = onResume,
        onCancel = onCancel,
    )
}

@Composable
fun DownloadsContent(
    tasks: List<DownloadTaskUi>,
    onPause: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("下载", style = MaterialTheme.typography.headlineSmall)
        if (tasks.isEmpty()) {
            Text(
                "暂无下载任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        ) {
            items(tasks, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Tokens.spacingM)) {
                        Text(task.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${task.statusLabel} · ${task.done}/${task.total} 字节 · 分片 ${task.id}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        task.error?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.spacingM)) {
                            if (task.canPause) {
                                TextButton(onClick = { onPause(task.id) }) { Text("暂停") }
                            }
                            if (task.canResume) {
                                TextButton(onClick = { onResume(task.id) }) { Text("续传") }
                            }
                            TextButton(onClick = { onCancel(task.id) }) { Text("取消") }
                        }
                    }
                }
            }
        }
    }
}
