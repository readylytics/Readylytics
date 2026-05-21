package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
): CartesianMarkerVisibilityListener =
    remember(onPointSelected) {
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
                        onPointSelected(
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

/**
 * A high-fidelity overlay that draws standard vertical guideline indicators and concentric circles.
 * It is passive and accepts pre-snapped coordinate bounds in the chart's canvas space.
 */
@Composable
fun VicoChartTooltipOverlay(
    selectedPointOffset: Offset?,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 180.dp,
) {
    var containerWidthPx by remember { mutableStateOf(0f) }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(chartHeight)
                .onSizeChanged { size ->
                    containerWidthPx = size.width.toFloat()
                },
    ) {
        if (selectedPointOffset != null && containerWidthPx > 0) {
            val tapX = selectedPointOffset.x.coerceIn(0f, containerWidthPx)
            val tapY = selectedPointOffset.y

            val primaryColor = MaterialTheme.colorScheme.primary

            Canvas(modifier = Modifier.fillMaxSize()) {
                // Vertical indicator line through the chart
                drawLine(
                    color = primaryColor.copy(alpha = 0.4f),
                    start = Offset(tapX, 0f),
                    end = Offset(tapX, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )

                // Concentric highlight circles (Material Design 3 style)
                drawCircle(
                    color = primaryColor.copy(alpha = 0.2f),
                    center = Offset(tapX, tapY),
                    radius = 8.dp.toPx(),
                )
                drawCircle(
                    color = primaryColor,
                    center = Offset(tapX, tapY),
                    radius = 4.dp.toPx(),
                )
            }
        }
    }
}
