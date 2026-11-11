package feature.shelf

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@Composable
fun ShelfScreen(onOpenDetail: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("书架")
        // TODO(M5): 接 ShelfRepository.observeShelf + 分页 + 封面；文案进本地化。
        Button(onClick = { onOpenDetail("demo-work") }) { Text("打开详情") }
    }
}
