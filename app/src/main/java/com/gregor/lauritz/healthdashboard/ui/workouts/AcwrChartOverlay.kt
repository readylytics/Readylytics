package com.gregor.lauritz.healthdashboard.ui.workouts

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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Canvas overlay for the ACWR (Training Load) chart.
 *
 * When a data column is selected it renders three simultaneous visual effects:
 * 1. A vertical guideline line through the chart at the selected x position.
 * 2. A pulsing rounded-rectangle **outline** around the selected TRIMP bar.
 * 3. Pulsing concentric circles (halo + solid dot) around the selected Strain Ratio node.
 *
 * Animated content is delegated to the private [AcwrChartOverlayContent] composable so that
 * calls to [rememberInfiniteTransition] and [animateFloat] are never made inside a conditional
 * block, satisfying the Compose runtime's static composition rules.
 *
 * @param selectedState  Active selection produced by [rememberAcwrMarkerVisibilityListener];
 *                       null means nothing is drawn and no animation runs.
 * @param trimpColor     Fill colour for the TRIMP bar series (used for bar outline).
 * @param ratioColor     Fill colour for the Strain Ratio line series (used for dot halo).
 * @param barThicknessDp Width of the column bars as declared in the Vico layer (default 8 dp).
 * @param chartHeight    Height of the chart host; the overlay must match this exactly.
 */
@Composable
fun AcwrChartOverlay(
    selectedState: AcwrSelectedState?,
    trimpColor: Color,
    ratioColor: Color,
    barThicknessDp: Dp = 8.dp,
    chartHeight: Dp = 220.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(chartHeight),
    ) {
        if (selectedState != null) {
            AcwrChartOverlayContent(
                selectedState = selectedState,
                trimpColor = trimpColor,
                ratioColor = ratioColor,
                barThicknessDp = barThicknessDp,
            )
        }
    }
}

/**
 * Draws the animated selection visuals for a single [AcwrSelectedState].
 *
 * Extracted into its own composable so [rememberInfiniteTransition] and [animateFloat] are
 * always called unconditionally at the top level of a composable, never inside an `if` block.
 */
@Composable
private fun AcwrChartOverlayContent(
    selectedState: AcwrSelectedState,
    trimpColor: Color,
    ratioColor: Color,
    barThicknessDp: Dp,
) {
    // Animations only run while a point is selected; they start from their
    // initial values on every new tap, giving a predictable "fresh pulse" feel.
    val infiniteTransition = rememberInfiniteTransition(label = "acwrHaloTransition")
    val haloRadiusCoeff by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "acwrHaloRadiusCoeff",
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "acwrHaloAlpha",
    )
    // Bar stroke pulses at its own rate for a visually distinct feel from the dot.
    val barStrokeCoeff by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.8f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "acwrBarStrokeCoeff",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        // size.width is used directly — no external measurement state needed.
        val canvasX = selectedState.canvasX.coerceIn(0f, size.width)

        // 1. Vertical guideline
        drawLine(
            color = ratioColor.copy(alpha = 0.35f),
            start = Offset(canvasX, 0f),
            end = Offset(canvasX, size.height),
            strokeWidth = 1.5.dp.toPx(),
        )

        // 2. Pulsing bar outline (only when bar canvas position is known)
        selectedState.barCanvasYTop?.let { barTop ->
            val halfBar = (barThicknessDp / 2).toPx()
            val barLeft = canvasX - halfBar
            val barHeight = size.height - barTop
            if (barHeight > 0f) {
                // Translucent halo fill behind the stroke outline
                drawRoundRect(
                    color = trimpColor.copy(alpha = haloAlpha * 0.5f),
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barThicknessDp.toPx(), barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                // Animated stroke outline
                drawRoundRect(
                    color = trimpColor.copy(alpha = 0.6f + haloAlpha * 0.4f),
                    topLeft = Offset(barLeft, barTop),
                    size = Size(barThicknessDp.toPx(), barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx() * barStrokeCoeff),
                )
            }
        }

        // 3. Pulsing halo + solid dot on the Strain Ratio line node
        selectedState.lineCanvasY?.let { dotY ->
            val dotCenter = Offset(canvasX, dotY)
            // Breathing outer halo
            drawCircle(
                color = ratioColor.copy(alpha = haloAlpha),
                center = dotCenter,
                radius = 8.dp.toPx() * haloRadiusCoeff,
            )
            // Solid inner dot
            drawCircle(
                color = ratioColor,
                center = dotCenter,
                radius = 4.dp.toPx(),
            )
        }
    }
}
