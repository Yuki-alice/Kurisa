package feature.detail

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

/** 作品详情（章节列表虚拟化、更新角标、下载粒度随 M5）。 */
@Serializable
data class DetailRoute(val workId: String) : NavKey

@Composable
fun DetailScreen(
    workId: String,
    onOpenReader: (workId: String, chapterId: String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("详情：$workId")
        // TODO(M5): 章节列表 + 下载（单章/范围/全部）+ 加入书架。
        Button(onClick = { onOpenReader(workId, "c1") }) { Text("开始阅读") }
        Button(onClick = onBack) { Text("返回") }
    }
}
