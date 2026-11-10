package spike

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/**
 * MD3 Expressive 的补缺口层。
 *
 * CMP 1.12.2 官方默认组合（material3 1.12.0-alpha03）已提供
 * `MaterialExpressiveTheme` / `HorizontalFloatingToolbar` / `ButtonGroup` /
 * `SplitButtonLayout` / `LoadingIndicator` 等官方组件，优先直接调用。
 * 本文件只保留官方未覆盖或本项目特有的形态（shape morph、表达型容器等），
 * 用 Compose foundation / ui / animation 原语自绘，commonMain 一份三端共用（C3）。
 */
@Composable
fun ExpressiveFloatingToolbar(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val corner by animateDpAsState(
        targetValue = if (expanded) 28.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "toolbarShapeMorph",
    )
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun ExpressiveButtonGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val corner by animateDpAsState(
                targetValue = if (index == selectedIndex) 50.dp else 12.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "groupShapeMorph",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(corner))
                    .background(
                        if (index == selectedIndex) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (index == selectedIndex) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ExpressiveLoadingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loadingIndicator")
    val sweep by transition.animateFloat(
        initialValue = 40f,
        targetValue = 290f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sweep",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1400)),
        label = "rotation",
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(48.dp)) {
        rotate(rotation) {
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = 6.dp.toPx()),
            )
        }
    }
}
