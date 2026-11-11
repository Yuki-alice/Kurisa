package feature.search

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

/** 全局搜索（跨源聚合、去重、筛选随 M5）。 */
@Serializable
data object SearchRoute : NavKey

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val workIds: List<String> = emptyList(),
)

class SearchStateHolder {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun query(q: String) {
        _state.value = _state.value.copy(query = q)
    }
}

@Composable
fun SearchScreen(onOpenDetail: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(Tokens.spacingL),
        verticalArrangement = Arrangement.spacedBy(Tokens.spacingM),
    ) {
        Text("搜索")
        // TODO(M5): 搜索框 + 跨源聚合 + optionList 默认值（M1 冻结语义）。
        Button(onClick = { onOpenDetail("demo-work") }) { Text("打开详情") }
    }
}
