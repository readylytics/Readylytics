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

/**
 * Goal-style bars/gauges (RAS, Steps) fill only this fraction of their track/arc at the goal or
 * max value, leaving headroom in the remaining width/sweep to visually show overshoot past goal.
 */
const val GOAL_FILL_CAP_FRACTION: Float = 0.75f

internal val METRIC_BAR_TICK_FRACTIONS: List<Float> = listOf(0.2f, 0.4f, 0.6f, 0.8f)

internal fun visibleTickFractions(
    progress: Float,
    capCoverageFraction: Float = 0f,
): List<Float> = METRIC_BAR_TICK_FRACTIONS.filter { it > progress + capCoverageFraction }

// The fill's round cap overhangs `strokeWidth / 2` past its center, so its center must be clamped
// to `[strokeWidth/2, width - strokeWidth/2]`: otherwise at 100% the cap overshoots the track end.
internal fun fillEndCenterX(
    progress: Float,
    width: Float,
    strokeWidth: Float,
): Float {
    val half = strokeWidth / 2f
    return (width * progress).coerceIn(half, (width - half).coerceAtLeast(half))
}

// Shared geometry primitive: the fraction of a track's total length/sweep that a round stroke cap
// overhangs past its center (strokeWidth / 2 of it). Used by M3MetricBar's linear track and
// M3MetricGauge's angular arc so both hide ticks that would otherwise render on top of the fill.
internal fun roundCapOverhangFraction(
    strokeWidth: Float,
    totalLength: Float,
): Float = if (strokeWidth > 0f && totalLength > 0f) (strokeWidth / 2f) / totalLength else 0f

// Fraction of the bar width the fill's round cap overhangs past its center. Zero progress or a
// zero-width canvas (early/collapsing composition frame) must yield 0f, never Infinity.
internal fun capCoverageFraction(
    progress: Float,
    width: Float,
    strokeWidth: Float,
): Float = if (progress > 0f) roundCapOverhangFraction(strokeWidth, width) else 0f

// The value marker dot is strictly opt-in: legacy callers (StepsCard, RasWeeklyBar) render a bare
// track+fill+ticks and must not draw a marker by default.
internal fun shouldDrawValueMarker(
    showMarker: Boolean,
    progressFraction: Float?,
    progressToDraw: Float,
): Boolean = showMarker && progressFraction != null && progressToDraw > 0f

@Composable
fun M3MetricBar(
    progressFraction: Float?,
    activeColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    tickColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    barHeight: Dp = MaterialTheme.dimens.metricTrackThickness,
    markerColor: Color = activeColor,
    markerDiameter: Dp = MaterialTheme.dimens.metricGaugeMarkerDiameter,
    showMarker: Boolean = false,
    animateProgress: Boolean = true,
) {
    val clamped = progressFraction?.coerceIn(0f, 1f) ?: 0f
    val progressToDraw =
        if (animateProgress) {
            val animated by animateFloatAsState(
                targetValue = clamped,
                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
                label = "bar_progress",
            )
            animated
        } else {
            clamped
        }
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
        // The fill's round cap overhangs `strokeWidth / 2` px past the raw progress fraction, so a
        // tick nominally just past `progressToDraw` can still sit inside that cap and render on top
        // of the fill (ticks are drawn after the fill). Hide ticks that fall within the overhang.
        visibleTickFractions(
            progressToDraw,
            capCoverageFraction(progressToDraw, size.width, strokeWidth),
        ).forEach { fraction ->
            drawCircle(
                color = tickColor,
                radius = tickRadiusPx,
                center = Offset(size.width * fraction, centerY),
            )
        }
        // Value marker dot sitting exactly at the visual end of the fill's rounded cap, mirroring
        // the gauge's marker (drawn last, on top of track/fill/ticks). Strictly opt-in via
        // [showMarker] so pre-existing callers never render it by accident.
        if (shouldDrawValueMarker(showMarker, progressFraction, progressToDraw)) {
            drawCircle(
                color = markerColor,
                radius = markerDiameter.toPx() / 2f,
                center = Offset(fillEndCenterX(progressToDraw, size.width, strokeWidth), centerY),
            )
        }
    }
}
