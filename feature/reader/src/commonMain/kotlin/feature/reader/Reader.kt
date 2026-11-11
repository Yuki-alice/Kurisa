package feature.reader

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

/** 阅读器（漫画 & 小说共用外壳，内部分支；内核见 M3，`:core:reader` 状态机）。 */
@Serializable
data class ReaderRoute(val workId: String, val chapterId: String) : NavKey

@Composable
fun ReaderScreen(workId: String, chapterId: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("阅读：$workId / $chapterId")
        // TODO(M3): ReaderViewModel 订阅 ReaderStateMachine；单页 ZoomImage / 长条自绘。
        Button(onClick = onBack) { Text("返回") }
    }
}
