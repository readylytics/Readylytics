package app.readylytics.health.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.readylytics.health.core.designsystem.dimens

internal val METRIC_BAR_TICK_FRACTIONS: List<Float> = listOf(0.2f, 0.4f, 0.6f, 0.8f)

internal fun visibleTickFractions(progress: Float): List<Float> = METRIC_BAR_TICK_FRACTIONS.filter { it > progress }

// The fill's round cap overhangs `strokeWidth / 2` past its center, so its center must be clamped
// to `[strokeWidth/2, width - strokeWidth/2]`: otherwise at 100% the cap overshoots the track end.
internal fun fillEndCenterX(
    progress: Float,
    width: Float,
    strokeWidth: Float,
): Float = (width * progress).coerceIn(strokeWidth / 2f, width - strokeWidth / 2f)

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

    Canvas(
        modifier =
            modifier
                .height(barHeight)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progressToDraw, 0f..1f)
                },
    ) {
        // One continuous track capsule spanning the full width; the fill overlays it with the same
        // round cap, so the track never reads as a second pill alongside the fill. (M3's
        // LinearProgressIndicator instead draws the track as the remainder after the fill, which is
        // what produced the "two pills" split.)
        val strokeWidth = size.height
        val centerY = size.height / 2f
        drawLine(
            color = trackColor,
            start = Offset(strokeWidth / 2f, centerY),
            end = Offset(size.width - strokeWidth / 2f, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        if (progressToDraw > 0f) {
            drawLine(
                color = activeColor,
                start = Offset(strokeWidth / 2f, centerY),
                end = Offset(fillEndCenterX(progressToDraw, size.width, strokeWidth), centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        val tickRadiusPx = tickDiameter.toPx() / 2f
        visibleTickFractions(progressToDraw).forEach { fraction ->
            drawCircle(
                color = tickColor,
                radius = tickRadiusPx,
                center = Offset(size.width * fraction, centerY),
            )
        }
    }
}
