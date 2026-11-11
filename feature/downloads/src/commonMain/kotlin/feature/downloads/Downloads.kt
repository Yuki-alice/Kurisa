package feature.downloads

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

/** 下载队列（执行引擎 Ketch + `:data:download` 编排，M3 接入）。 */
@Serializable
data object DownloadsRoute : NavKey

@Composable
fun DownloadsScreen() {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("下载")
        // TODO(M3): 接 DownloadController.tasks（优先级/暂停/续传）。
    }
}
