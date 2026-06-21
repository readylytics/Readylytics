package app.readylytics.health.ui.workouts

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
 * @param layerBounds    The bounding box of the chart data area.
 * @param modifier       The modifier to be applied to the layout.
 * @param barThicknessDp Width of the column bars as declared in the Vico layer (default 8 dp).
 * @param chartHeight    Height of the chart host; the overlay must match this exactly.
 */
@Composable
fun AcwrChartOverlay(
    selectedState: AcwrSelectedState?,
    trimpColor: Color,
    ratioColor: Color,
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
            AcwrChartOverlayContent(
                selectedState = selectedState,
                trimpColor = trimpColor,
                ratioColor = ratioColor,
                layerBounds = layerBounds,
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
    layerBounds: Rect?,
    barThicknessDp: Dp,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "AcwrOverlay")

    // Pulse animation for bar stroke and dot halo
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "PulseAlpha",
    )

    // Breathing effect for halo radius
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.3f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "PulseScale",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 2.dp.toPx()
        val barWidthPx = barThicknessDp.toPx()
        val guideColor = Color.White.copy(alpha = 0.3f)

        // 1. Draw Vertical Guideline
        // Clip to layer bounds if provided so we don't draw over axes
        if (layerBounds != null) {
            drawLine(
                color = guideColor,
                start = Offset(selectedState.canvasX, layerBounds.top),
                end = Offset(selectedState.canvasX, layerBounds.bottom),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // 2. Draw Pulsing TRIMP Bar Outline
        selectedState.barCanvasYTop?.let { yTop ->
            if (layerBounds != null) {
                drawRoundRect(
                    color = trimpColor.copy(alpha = pulseAlpha),
                    topLeft = Offset(selectedState.canvasX - (barWidthPx / 2), yTop),
                    size = Size(barWidthPx, layerBounds.bottom - yTop),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth),
                )
            }
        }

        // 3. Draw Pulsing Ratio Dot
        selectedState.lineCanvasY?.let { yLine ->
            val ratioPoint = Offset(selectedState.canvasX, yLine)
            // Halo
            drawCircle(
                color = ratioColor.copy(alpha = pulseAlpha * 0.5f),
                radius = 8.dp.toPx() * pulseScale,
                center = ratioPoint,
            )
            // Inner Glow
            drawCircle(
                color = ratioColor.copy(alpha = pulseAlpha),
                radius = 6.dp.toPx(),
                center = ratioPoint,
            )
            // Solid Core
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = ratioPoint,
            )
        }
    }
}
