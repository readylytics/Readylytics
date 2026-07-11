package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.readylytics.health.domain.util.ProjectedPoint

@Composable
fun RouteContourCard(
    routeUiState: RouteUiState,
    modifier: Modifier = Modifier,
) {
    val routeColor = MaterialTheme.colorScheme.primary
    val scaleColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val transform =
                calculateRouteContourTransform(
                    points = routeUiState.points,
                    canvasWidth = size.width,
                    canvasHeight = size.height,
                    padding = 20.dp.toPx(),
                ) ?: return@Canvas
            val path = Path()
            routeUiState.points.forEachIndexed { index, point ->
                val canvasPoint = point.toCanvasOffset(transform, size.height)
                if (index == 0) path.moveTo(canvasPoint.x, canvasPoint.y) else path.lineTo(canvasPoint.x, canvasPoint.y)
            }

            drawPath(
                path = path,
                color = routeColor,
                style = Stroke(width = 3.dp.toPx()),
            )
            if (routeUiState.scaleLineWidthDp > 0f) {
                val scaleWidth = routeUiState.scaleLineWidthDp.dp.toPx()
                val start = Offset(x = size.width - 20.dp.toPx() - scaleWidth, y = size.height - 20.dp.toPx())
                drawLine(
                    color = scaleColor,
                    start = start,
                    end = start.copy(x = start.x + scaleWidth),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
    }
}

internal data class RouteContourTransform(
    val minX: Double,
    val minY: Double,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
)

internal fun calculateRouteContourTransform(
    points: List<ProjectedPoint>,
    canvasWidth: Float,
    canvasHeight: Float,
    padding: Float,
): RouteContourTransform? {
    if (points.isEmpty() || canvasWidth <= 0f || canvasHeight <= 0f) return null

    val minX = points.minOf { it.x }
    val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }
    val maxY = points.maxOf { it.y }
    val geoWidth = (maxX - minX).toFloat()
    val geoHeight = (maxY - minY).toFloat()
    val availableWidth = (canvasWidth - 2 * padding).coerceAtLeast(0f)
    val availableHeight = (canvasHeight - 2 * padding).coerceAtLeast(0f)
    val scale =
        when {
            geoWidth > 0f && geoHeight > 0f -> minOf(availableWidth / geoWidth, availableHeight / geoHeight)
            geoWidth > 0f -> availableWidth / geoWidth
            geoHeight > 0f -> availableHeight / geoHeight
            else -> 1f
        }

    return RouteContourTransform(
        minX = minX,
        minY = minY,
        scale = scale,
        offsetX = (canvasWidth - geoWidth * scale) / 2f,
        offsetY = (canvasHeight - geoHeight * scale) / 2f,
    )
}

private fun ProjectedPoint.toCanvasOffset(
    transform: RouteContourTransform,
    canvasHeight: Float,
): Offset =
    Offset(
        x = transform.offsetX + (x - transform.minX).toFloat() * transform.scale,
        y = canvasHeight - transform.offsetY - (y - transform.minY).toFloat() * transform.scale,
    )
