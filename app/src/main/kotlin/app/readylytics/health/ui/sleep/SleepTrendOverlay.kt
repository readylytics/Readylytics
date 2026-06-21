package app.readylytics.health.ui.sleep

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Canvas overlay for the Sleep Trend chart.
 *
 * Duplicates the visual feedback of the ACWR chart, modified to fit the sleep data.
 */
@Composable
fun SleepTrendOverlay(
    selectedState: SleepTrendSelectedState?,
    barColor: Color,
    lineColor: Color,
    layerBounds: Rect?,
    modifier: Modifier = Modifier,
    barThicknessDp: Dp = 8.dp,
    chartHeight: Dp = 220.dp,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(chartHeight),
    ) {
        if (selectedState != null) {
            SleepTrendOverlayContent(
                selectedState = selectedState,
                barColor = barColor,
                lineColor = lineColor,
                layerBounds = layerBounds,
                barThicknessDp = barThicknessDp,
            )
        }
    }
}

@Composable
private fun SleepTrendOverlayContent(
    selectedState: SleepTrendSelectedState,
    barColor: Color,
    lineColor: Color,
    layerBounds: Rect?,
    barThicknessDp: Dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sleepTrendHaloTransition")
    val haloRadiusCoeff by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "sleepTrendHaloRadiusCoeff",
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "sleepTrendHaloAlpha",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasX = selectedState.canvasX.coerceIn(0f, size.width)

        // 1. Vertical guideline
        drawLine(
            color = lineColor.copy(alpha = 0.35f),
            start = Offset(canvasX, 0f),
            end = Offset(canvasX, size.height),
            strokeWidth = 1.5.dp.toPx(),
        )

        // 2. Pulsing bar outline for the sleep span (Series 1)
        val barTop = selectedState.barCanvasYTop
        val barBottom = selectedState.barCanvasYBottom
        if (barTop != null && barBottom != null) {
            val halfBar = (barThicknessDp / 2).toPx()
            val barLeft = canvasX - halfBar
            val barHeight = barBottom - barTop
            if (barHeight > 0f) {
                val currentHaloPadding = 4.dp.toPx() * haloRadiusCoeff
                drawRoundRect(
                    color = barColor.copy(alpha = haloAlpha),
                    topLeft = Offset(barLeft - currentHaloPadding, barTop - currentHaloPadding),
                    size = Size(barThicknessDp.toPx() + 2 * currentHaloPadding, barHeight + 2 * currentHaloPadding),
                    cornerRadius = CornerRadius(2.dp.toPx() + currentHaloPadding),
                )
            }
        }

        // 3. Pulsing halo + solid dot on the actual duration line node
        selectedState.lineCanvasY?.let { dotY ->
            val dotCenter = Offset(canvasX, dotY)
            drawCircle(
                color = lineColor.copy(alpha = haloAlpha),
                center = dotCenter,
                radius = 8.dp.toPx() * haloRadiusCoeff,
            )
            drawCircle(
                color = lineColor,
                center = dotCenter,
                radius = 4.dp.toPx(),
            )
        }
    }
}
