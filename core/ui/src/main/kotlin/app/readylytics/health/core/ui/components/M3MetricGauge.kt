package app.readylytics.health.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.core.designsystem.spacing

@Composable
fun metricVisualizationTrackColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

@Composable
fun M3MetricGauge(
    markerFraction: Float?,
    activeColor: Color,
    modifier: Modifier = Modifier,
    animateMarker: Boolean = true,
) {
    val clampedFraction = markerFraction?.coerceIn(0f, 1f)
    val trackColor = metricVisualizationTrackColor()

    val animatedProgress by animateFloatAsState(
        targetValue = clampedFraction ?: 0f,
        animationSpec =
            if (animateMarker) {
                tween(
                    durationMillis = 800,
                    easing = FastOutSlowInEasing,
                )
            } else {
                tween(durationMillis = 0)
            },
        label = "gauge_progress",
    )

    val progressToDraw = if (animateMarker) animatedProgress else (clampedFraction ?: 0f)

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(
            modifier =
                Modifier
                    .width(120.dp)
                    .height(60.dp)
                    .padding(bottom = MaterialTheme.spacing.extraSmallMedium),
        ) {
            val strokeWidthPx = 8.dp.toPx()

            val horizontalPadding = strokeWidthPx / 2f
            val verticalPadding = strokeWidthPx / 2f

            val arcWidth = size.width - 2 * horizontalPadding
            val radius = arcWidth / 2f
            val centerX = size.width / 2f
            val centerY = size.height - verticalPadding

            val topLeft = Offset(centerX - radius, centerY - radius)
            val arcSize = Size(radius * 2, radius * 2)

            drawArc(
                color = trackColor,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
            )

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
            }
        }
    }
}

@Composable
fun M3MetricGaugeWithValue(
    markerFraction: Float?,
    activeColor: Color,
    valueText: String,
    unitText: String,
    valueColor: Color,
    unitColor: Color,
    modifier: Modifier = Modifier,
    animateMarker: Boolean = true,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        M3MetricGauge(
            markerFraction = markerFraction,
            activeColor = activeColor,
            animateMarker = animateMarker,
            modifier = Modifier.fillMaxWidth(),
        )

        androidx.compose.foundation.layout.Column(
            modifier = Modifier.offset(y = MaterialTheme.spacing.extraSmallMedium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            androidx.compose.material3.Text(
                text = valueText,
                style = MaterialTheme.typography.displaySmall,
                color = valueColor,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            androidx.compose.material3.Text(
                text = unitText.ifBlank { " " },
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                color = if (unitText.isNotBlank()) unitColor else Color.Transparent,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.offset(y = (-8).dp),
            )
        }
    }
}
