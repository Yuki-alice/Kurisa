package feature.explore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import kotlinx.serialization.Serializable

/** 按源探索页（分类/榜单随 M5）。 */
@Serializable
data class ExploreRoute(val sourceKey: String) : NavKey

@Composable
fun ExploreScreen(sourceKey: String, onOpenDetail: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("探索：$sourceKey")
        // TODO(M5): 接源 explore/category 能力 + 分页。
        Button(onClick = { onOpenDetail("demo-work") }) { Text("打开详情") }
    }
}
