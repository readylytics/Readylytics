package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.domain.util.ProjectedPoint
import app.readylytics.health.feature.workouts.R
import kotlin.math.pow

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
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val density = LocalDensity.current
            val canvasWidth = with(density) { maxWidth.toPx() }
            val canvasHeight = with(density) { 200.dp.toPx() }
            val padding = with(density) { 20.dp.toPx() }
            val transform =
                remember(routeUiState.points, canvasWidth, canvasHeight, padding) {
                    calculateRouteContourTransform(
                        points = routeUiState.points,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        padding = padding,
                    )
                }
            val contourScale =
                remember(transform, canvasWidth) {
                    transform?.let {
                        calculateRouteContourScale(
                            metersPerPixel = 1f / it.scale,
                            maxWidthPx = canvasWidth / 4f,
                        )
                    }
                }

            Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                val canvasTransform =
                    transform ?: return@Canvas
                val path = Path()
                routeUiState.points.forEachIndexed { index, point ->
                    val canvasPoint = point.toCanvasOffset(canvasTransform, size.height)
                    if (index ==
                        0
                    ) {
                        path.moveTo(canvasPoint.x, canvasPoint.y)
                    } else {
                        path.lineTo(canvasPoint.x, canvasPoint.y)
                    }
                }

                drawPath(
                    path = path,
                    color = routeColor,
                    style = Stroke(width = 3.dp.toPx()),
                )
                contourScale?.let { scale ->
                    val start = Offset(x = size.width - 20.dp.toPx() - scale.widthPx, y = size.height - 20.dp.toPx())
                    drawLine(
                        color = scaleColor,
                        start = start,
                        end = start.copy(x = start.x + scale.widthPx),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
            contourScale?.let { scale ->
                Text(
                    text = stringResource(R.string.workout_route_scale_meters, scale.distanceMeters),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp),
                    color = scaleColor,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

internal data class RouteContourScale(
    val distanceMeters: Int,
    val widthPx: Float,
)

internal fun calculateRouteContourScale(
    metersPerPixel: Float,
    maxWidthPx: Float,
): RouteContourScale {
    val targetDistance = (metersPerPixel * maxWidthPx / 2f).coerceAtLeast(1f)
    val exponent = kotlin.math.floor(kotlin.math.log10(targetDistance.toDouble())).toInt()
    val magnitude = 10.0.pow(exponent).toFloat()
    val distanceMeters = listOf(1f, 2f, 5f).last { it * magnitude <= targetDistance }.times(magnitude).toInt()
    return RouteContourScale(distanceMeters = distanceMeters, widthPx = distanceMeters / metersPerPixel)
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
