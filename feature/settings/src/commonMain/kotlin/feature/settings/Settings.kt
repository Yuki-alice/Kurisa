package feature.settings

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

/** 设置（主题/阅读/网络/存储/关于；MD3E 三端截图基线随 M5）。 */
@Serializable
data object SettingsRoute : NavKey

@Composable
fun SettingsScreen() {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("设置")
        // TODO(M5): 主题/阅读/网络/存储/关于 + 开源许可页（AboutLibraries）。
    }
}
