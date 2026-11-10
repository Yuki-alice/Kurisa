package spike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * CMP 1.12.2 官方默认组合：material3 1.12.0-alpha03 直接提供 Expressive 组件。
 * 自绘组件（ExpressiveComponents.kt）保留为补缺口层，供官方未覆盖的形态使用。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpikeApp() {
    MaterialExpressiveTheme {
        Column(Modifier.padding(16.dp)) {
            Text("M0 spike")
            Button(onClick = {}) { Text("MD3 baseline") }
            HorizontalFloatingToolbar(expanded = true) {
                Text("MD3E official component")
            }
            LoadingIndicator()
            // 补缺口层：官方未覆盖的 shape morph 形态，仍走自绘
            ExpressiveFloatingToolbar(expanded = false) {
                Text("supplement")
            }
        }
    }
}
