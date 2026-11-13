package feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import kotlinx.serialization.Serializable

/**
 * 按源探索页（分类/榜单随 M5）。
 *
 * 明确标注未接线：源侧 `explore` / `category` 能力已在引擎层，但本页没有消费方，
 * 不放「打开详情」这类假入口（演示 id 会让验证者误以为链路已通）。
 */
@Serializable
data class ExploreRoute(val sourceKey: String) : NavKey

@Composable
fun ExploreScreen(
    sourceKey: String,
    onBack: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("探索：$sourceKey", style = MaterialTheme.typography.headlineSmall)
        Text(
            "该源的 explore/category 浏览尚未接线（引擎能力已在，UI 出口随下一阶段）。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onBack) { Text("返回") }
    }
}
