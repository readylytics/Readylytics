package app.readylytics.health.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class ProgressBarSegment(
    val value: Float,
    val color: Color,
)

@Composable
fun HealthProgressBar(
    segments: List<ProgressBarSegment>,
    max: Float,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    outlineColor: Color? = MaterialTheme.colorScheme.outlineVariant,
    onDrawOverlay: DrawScope.(totalWidth: Float, barHeight: Float) -> Unit = { _, _ -> },
) {
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height),
    ) {
        val totalWidth = size.width
        val barHeight = size.height
        val radius = barHeight / 2f
        val coerceMax = max.coerceAtLeast(0.001f)

        val clipPath =
            Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = totalWidth,
                        bottom = barHeight,
                        cornerRadius = CornerRadius(radius),
                    ),
                )
            }

        clipPath(clipPath) {
            // Draw background track
            drawRect(
                color = trackColor,
                topLeft = Offset(0f, 0f),
                size = Size(totalWidth, barHeight),
            )

            // Draw segments sequentially
            var xOffset = 0f
            for (segment in segments) {
                if (segment.value > 0f) {
                    val segmentWidth = totalWidth * (segment.value / coerceMax)
                    val remainingWidth = (totalWidth - xOffset).coerceAtLeast(0f)
                    val drawWidth = segmentWidth.coerceAtMost(remainingWidth)
                    if (drawWidth > 0f) {
                        drawRect(
                            color = segment.color,
                            topLeft = Offset(xOffset, 0f),
                            size = Size(drawWidth, barHeight),
                        )
                        xOffset += drawWidth
                    }
                }
            }
        }

        // Draw outline border if outlineColor is provided
        if (outlineColor != null) {
            drawRoundRect(
                color = outlineColor,
                cornerRadius = CornerRadius(radius),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        // Invoke optional overlay drawing lambda
        onDrawOverlay(totalWidth, barHeight)
    }
}

/**
 * A simple overload of [HealthProgressBar] for a single progress value.
 */
@Composable
fun HealthProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    outlineColor: Color? = MaterialTheme.colorScheme.outlineVariant,
) {
    HealthProgressBar(
        segments = listOf(ProgressBarSegment(value = progress, color = color)),
        max = 1f,
        modifier = modifier,
        height = height,
        trackColor = trackColor,
        outlineColor = outlineColor,
    )
}
