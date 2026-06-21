package app.readylytics.health.ui.components

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget

/**
 * A custom invisible [CartesianMarker] that allows Vico to handle gestures, touch
 * tracking, zooming, and scrolling natively while rendering nothing itself.
 * The actual visual feedback is rendered using the standard Jetpack Compose Canvas overlay.
 */
object InvisibleMarker : CartesianMarker {
    override fun drawUnderLayers(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
    ) {
        // Do nothing to keep the marker invisible.
    }

    override fun drawOverLayers(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
    ) {
        // Do nothing to keep the marker invisible.
    }
}

/**
 * A Compose helper to create and remember a [CartesianMarkerVisibilityListener].
 * This listener catches target updates from Vico's native touch detection engine,
 * parses out the active line point targets, and returns snapped data values and canvas coordinates.
 */
@Composable
fun rememberChartMarkerVisibilityListener(
    onPointSelected: (x: Double, y: Double, canvasX: Float, canvasY: Float) -> Unit,
): CartesianMarkerVisibilityListener {
    // Capture the latest callback WITHOUT invalidating the remembered listener object.
    // The previous `remember(onPointSelected)` keyed the listener on the inline lambda,
    // which captures volatile state (points, tooltipState). During a pinch, Vico fires
    // onUpdated → the callback writes state → recomposition → a new lambda instance →
    // the listener object was recreated mid-gesture, resetting Vico's marker/gesture
    // tracking and throttling pinch-zoom to a stutter. Mirroring the ACWR chart's stable
    // listener (rememberUpdatedState + keyless remember) keeps the object identity fixed
    // so the transform gesture is never interrupted. See Vico issue #1054.
    val currentOnPointSelected = rememberUpdatedState(onPointSelected)
    return remember {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(
                marker: CartesianMarker,
                targets: List<CartesianMarker.Target>,
            ) {
                handleTargets(targets)
            }

            override fun onUpdated(
                marker: CartesianMarker,
                targets: List<CartesianMarker.Target>,
            ) {
                handleTargets(targets)
            }

            override fun onHidden(marker: CartesianMarker) {
                // Do not clear state automatically on finger lift, allowing the
                // custom Compose tooltip to remain visible until explicitly dismissed.
            }

            private fun handleTargets(targets: List<CartesianMarker.Target>) {
                val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                if (target != null) {
                    val point = target.points.firstOrNull()
                    if (point != null) {
                        currentOnPointSelected.value(
                            target.x,
                            point.entry.y,
                            target.canvasX,
                            point.canvasY,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A high-fidelity overlay that draws standard vertical guideline indicators and concentric circles.
 * It is passive and accepts pre-snapped coordinate bounds in the chart's canvas space.
 */
@Composable
fun VicoChartTooltipOverlay(
    selectedPointOffset: Offset?,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
    pulseColor: Color = MaterialTheme.colorScheme.primary,
    externalCanvasX: Float? = null,
    externalDataY: Double? = null,
    minY: Double? = null,
    maxY: Double? = null,
) {
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    // Breathing halo animation on selection
    val infiniteTransition = rememberInfiniteTransition(label = "vicoHaloTransition")
    val haloRadiusCoeff by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "vicoHaloRadiusCoeff",
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.4f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "vicoHaloAlpha",
    )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(chartHeight)
                .onSizeChanged { size ->
                    containerWidthPx = size.width.toFloat()
                    containerHeightPx = size.height.toFloat()
                },
    ) {
        val tapX = selectedPointOffset?.x ?: externalCanvasX
        if (tapX != null && containerWidthPx > 0 && containerHeightPx > 0) {
            val clampedTapX = tapX.coerceIn(0f, containerWidthPx)

            val tapY =
                if (selectedPointOffset != null) {
                    selectedPointOffset.y
                } else if (externalDataY != null && minY != null && maxY != null) {
                    val yRatio = ((maxY - externalDataY) / (maxY - minY)).coerceIn(0.0, 1.0).toFloat()
                    // Approximate Vico layer bounds (top padding ~8dp, bottom axis ~24dp).
                    // A +1dp correction compensates for the systematic upward shift observed
                    // in the split-chart coordinated mode.
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val topPad = with(density) { 8.dp.toPx() }
                    val bottomPad = with(density) { 24.dp.toPx() }
                    val correction = with(density) { 1.dp.toPx() }
                    topPad + yRatio * (containerHeightPx - topPad - bottomPad) + correction
                } else {
                    null
                }

            val primaryColor = MaterialTheme.colorScheme.primary

            Canvas(modifier = Modifier.fillMaxSize()) {
                // Vertical indicator line through the chart
                drawLine(
                    color = primaryColor.copy(alpha = 0.4f),
                    start = Offset(clampedTapX, 0f),
                    end = Offset(clampedTapX, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )

                if (tapY != null) {
                    // Concentric highlight circles with breathing pulsing animation
                    drawCircle(
                        color = pulseColor.copy(alpha = haloAlpha),
                        center = Offset(clampedTapX, tapY),
                        radius = (8.dp.toPx() * haloRadiusCoeff),
                    )
                    drawCircle(
                        color = pulseColor,
                        center = Offset(clampedTapX, tapY),
                        radius = 4.dp.toPx(),
                    )
                }
            }
        }
    }
}
