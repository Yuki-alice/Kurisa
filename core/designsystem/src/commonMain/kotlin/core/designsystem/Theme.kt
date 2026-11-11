package core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 主题唯一入口（M5 切到官方 `MaterialExpressiveTheme`；升级只改此处）。
 * 现用稳定版 MaterialTheme 占位，保证三端行为一致（C3）。
 */
@Composable
fun AppTheme(
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** 全局 tokens（禁止散落硬编码尺寸；动效参数后续随 MotionScheme 收口）。 */
object Tokens {
    val spacingS = 4.dp
    val spacingM = 8.dp
    val spacingL = 16.dp
    val spacingXl = 24.dp
    val coverAspect = 3f / 4f
}
