package feature.explore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import kotlinx.serialization.Serializable

/** 按源探索页（结果由 App 层从 `SourceCatalog.explore` 映射，feature 不认识装配层）。 */
@Serializable
data class ExploreRoute(val sourceKey: String) : NavKey

/** 发现结果展示行；[sourceKey] 随行带到详情，避免多源下回退首个 enabled 源。 */
data class ExploreWorkUi(
    val workId: String,
    val title: String,
    val sourceKey: String,
)

/** 一页发现结果（错误与结果并存，单源失败不拖垮整页）。 */
data class ExplorePageUi(
    val works: List<ExploreWorkUi> = emptyList(),
    val errors: List<String> = emptyList(),
)

/**
 * 最小功能入口：按源加载一页发现结果。
 * 视觉还原待 `design/` 原型定稿后整体换样，本轮只保证后端能力可触达。
 */
@Composable
fun ExploreScreen(
    sourceKey: String,
    onOpenDetail: (String) -> Unit,
    loader: (suspend (String, Int) -> ExplorePageUi)? = null,
) {
    var page by remember(sourceKey) { mutableStateOf<ExplorePageUi?>(null) }
    var loading by remember(sourceKey) { mutableStateOf(false) }
    LaunchedEffect(sourceKey, loader) {
        if (loader == null) return@LaunchedEffect
        loading = true
        page = runCatching { loader(sourceKey, 1) }
            .getOrElse { ExplorePageUi(errors = listOf(it.message ?: "发现加载失败")) }
        loading = false
    }
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text(
            if (sourceKey.isBlank()) "探索（全部启用源）" else "探索：$sourceKey",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (loader == null) {
            Text("未装配源目录", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        if (loading) CircularProgressIndicator()
        page?.errors.orEmpty().forEach { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
        ) {
            items(page?.works.orEmpty(), key = { "${it.sourceKey}|${it.workId}" }) { work ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenDetail(work.workId) },
                ) {
                    Column(Modifier.padding(Tokens.spacingM)) {
                        Text(work.title, style = MaterialTheme.typography.titleSmall)
                        Text(work.sourceKey, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (!loading && page != null && page!!.works.isEmpty() && page!!.errors.isEmpty()) {
            Text("暂无内容", style = MaterialTheme.typography.bodySmall)
        }
    }
}
