package app.readylytics.health.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class M3GaugeSegment(
    val startFraction: Float,
    val endFraction: Float,
    val color: Color,
)

@Composable
fun M3MetricGauge(
    markerFraction: Float?,
    activeColor: Color,
    segments: List<M3GaugeSegment>,
    modifier: Modifier = Modifier,
    animateMarker: Boolean = true,
) {
    val clampedFraction = markerFraction?.coerceIn(0f, 1f)
    
    val animatedProgress by animateFloatAsState(
        targetValue = clampedFraction ?: 0f,
        animationSpec = if (animateMarker) tween(durationMillis = 800, easing = FastOutSlowInEasing) else tween(durationMillis = 0),
        label = "gauge_progress",
    )

    val progressToDraw = if (animateMarker) animatedProgress else (clampedFraction ?: 0f)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(
            modifier = Modifier
                .width(120.dp)
                .height(60.dp)
                .padding(bottom = MaterialTheme.spacing.extraSmallMedium),
        ) {
            val strokeWidthPx = 8.dp.toPx()
            val dotRadiusPx = 5.dp.toPx()

            val horizontalPadding = strokeWidthPx / 2f + dotRadiusPx
            val verticalPadding = strokeWidthPx / 2f + dotRadiusPx

            val arcWidth = size.width - 2 * horizontalPadding
            val radius = arcWidth / 2f
            val centerX = size.width / 2f
            val centerY = size.height - verticalPadding

            val topLeft = Offset(centerX - radius, centerY - radius)
            val arcSize = Size(radius * 2, radius * 2)

            // Draw track segments
            if (segments.isEmpty()) {
                // If no segments are provided, draw a simple track
                drawArc(
                    color = Color.LightGray,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                )
            } else {
                segments.forEach { segment ->
                    val startAngle = 180f + (180f * segment.startFraction.coerceIn(0f, 1f))
                    val sweepAngle = 180f * (segment.endFraction.coerceIn(0f, 1f) - segment.startFraction.coerceIn(0f, 1f))
                    if (sweepAngle > 0f) {
                        drawArc(
                            color = segment.color,
                            startAngle = startAngle,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                        )
                    }
                }
            }

            // Draw active arc progress
            if (markerFraction != null && progressToDraw > 0f) {
                drawArc(
                    color = activeColor,
                    startAngle = 180f,
                    sweepAngle = 180f * progressToDraw,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                )

                // Draw endpoint dot
                val endAngle = 180f + (180f * progressToDraw)
                val endAngleRad = Math.toRadians(endAngle.toDouble())
                val dotX = centerX + radius * cos(endAngleRad).toFloat()
                val dotY = centerY + radius * sin(endAngleRad).toFloat()

                drawCircle(
                    color = activeColor,
                    radius = dotRadiusPx,
                    center = Offset(dotX, dotY),
                )
            }
        }
    }
}
