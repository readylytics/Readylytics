package app.readylytics.health.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens

internal val METRIC_BAR_TICK_FRACTIONS: List<Float> = listOf(0.2f, 0.4f, 0.6f, 0.8f)

internal fun visibleTickFractions(progress: Float): List<Float> = METRIC_BAR_TICK_FRACTIONS.filter { it > progress }

@Composable
fun M3MetricBar(
    progressFraction: Float?,
    activeColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    tickColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    barHeight: Dp = MaterialTheme.dimens.metricTrackThickness,
    animateProgress: Boolean = true,
) {
    val clamped = progressFraction?.coerceIn(0f, 1f) ?: 0f
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec =
            if (animateProgress) {
                tween(durationMillis = 800, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 0)
            },
        label = "bar_progress",
    )
    val progressToDraw = if (animateProgress) animated else clamped
    val tickDiameter = MaterialTheme.dimens.metricGaugeTickDiameter

    Box(modifier = modifier.height(barHeight)) {
        LinearProgressIndicator(
            progress = { progressToDraw },
            modifier = Modifier.fillMaxSize(),
            color = activeColor,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tickRadiusPx = tickDiameter.toPx() / 2f
            visibleTickFractions(progressToDraw).forEach { fraction ->
                drawCircle(
                    color = tickColor,
                    radius = tickRadiusPx,
                    center = Offset(size.width * fraction, size.height / 2f),
                )
            }
        }
    }
}
