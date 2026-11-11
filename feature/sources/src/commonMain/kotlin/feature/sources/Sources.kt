package feature.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import core.designsystem.Tokens
import kotlinx.serialization.Serializable

/** 源管理（安装/更新/权限/设置/日志/一键禁用，见 M2；UI 随 M5）。 */
@Serializable
data object SourcesRoute : NavKey

@Composable
fun SourcesScreen() {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("源管理")
        // TODO(M5): 接 :source:registry（安装/回滚/禁用）+ 审计日志展示。
    }
}
