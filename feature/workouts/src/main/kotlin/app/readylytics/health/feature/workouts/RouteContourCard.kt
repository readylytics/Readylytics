package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.util.ProjectedPoint

private val CONTOUR_PADDING = 16.dp

/**
 * Below this the distance grid stops being a reading aid and turns into noise. The scale ladder
 * keeps cells at 20-50% of the contour, so this only ever trips on a degenerate route.
 */
private val MIN_GRID_CELL = 12.dp

enum class RouteDataState {
    Available,
    PermissionRequired,
    NotAvailable,
}

data class RouteUiState(
    val state: RouteDataState = RouteDataState.NotAvailable,
    val projectedPoints: List<ProjectedPoint> = emptyList(),
    val scaleLabel: String = "",
    /**
     * Scale-bar length as a fraction of the drawn contour's square side. Sized against the real
     * canvas at draw time so the bar's length actually matches [scaleLabel].
     */
    val scaleWidthFraction: Float = 0f,
)

@Composable
fun RouteContourCard(
    uiState: RouteUiState,
    onGrantPermissionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.state == RouteDataState.NotAvailable) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(MaterialTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Text(
                text = stringResource(R.string.workout_route_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            when (uiState.state) {
                RouteDataState.PermissionRequired -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.small),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        Text(
                            text = stringResource(R.string.workout_route_permission_required_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onGrantPermissionClick,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(stringResource(R.string.workout_route_grant_permission))
                        }
                    }
                }
                RouteDataState.Available -> {
                    BoxWithConstraints(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.4f)
                                .padding(MaterialTheme.spacing.small),
                    ) {
                        val routeColor = MaterialTheme.colorScheme.primary
                        val startColor = Color(0xFF4CAF50)
                        val endColor = MaterialTheme.colorScheme.error
                        val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)

                        // RouteProjector emits an aspect-ratio-preserving [0,1] box, so both axes
                        // must share one scale factor. Mapping x across the full (wider) canvas
                        // would stretch the contour by the 1.4 aspect ratio.
                        val contourSideDp =
                            (minOf(maxWidth, maxHeight) - CONTOUR_PADDING * 2).coerceAtLeast(0.dp)
                        val gridDescription =
                            stringResource(
                                R.string.workout_route_grid_content_description,
                                uiState.scaleLabel,
                            )

                        Canvas(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .semantics { contentDescription = gridDescription },
                        ) {
                            val points = uiState.projectedPoints
                            if (points.size < 2) return@Canvas

                            val paddingPx = CONTOUR_PADDING.toPx()
                            val side = minOf(size.width, size.height) - 2 * paddingPx
                            if (side <= 0f) return@Canvas
                            val originX = paddingPx + (size.width - 2 * paddingPx - side) / 2f
                            val originY = paddingPx + (size.height - 2 * paddingPx - side) / 2f

                            fun offsetFor(p: ProjectedPoint) = Offset(originX + p.x * side, originY + p.y * side)

                            // Distance grid. RouteProjector normalises by the route's longest
                            // dimension, so the contour square's side IS that dimension -- a cell of
                            // `side * scaleWidthFraction` therefore measures exactly `scaleLabel` on
                            // both axes. Lines are anchored to the contour origin and extended across
                            // the whole canvas so the reader can step off distance anywhere, not just
                            // inside the square.
                            val cell = side * uiState.scaleWidthFraction
                            if (cell >= MIN_GRID_CELL.toPx()) {
                                val gridStroke = 1.dp.toPx() / 2f
                                var x = originX
                                while (x >= 0f) {
                                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), gridStroke)
                                    x -= cell
                                }
                                x = originX + cell
                                while (x <= size.width) {
                                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), gridStroke)
                                    x += cell
                                }
                                var y = originY
                                while (y >= 0f) {
                                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), gridStroke)
                                    y -= cell
                                }
                                y = originY + cell
                                while (y <= size.height) {
                                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), gridStroke)
                                    y += cell
                                }
                            }

                            val path = Path()
                            points.forEachIndexed { i, p ->
                                val o = offsetFor(p)
                                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                            }

                            drawPath(
                                path = path,
                                color = routeColor,
                                style =
                                    Stroke(
                                        width = 4.dp.toPx(),
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                            )

                            // Start point dot
                            drawCircle(
                                color = startColor,
                                radius = 5.dp.toPx(),
                                center = offsetFor(points.first()),
                            )

                            // End point dot
                            drawCircle(
                                color = endColor,
                                radius = 5.dp.toPx(),
                                center = offsetFor(points.last()),
                            )
                        }

                        // Scale bar
                        if (uiState.scaleLabel.isNotEmpty() && uiState.scaleWidthFraction > 0f) {
                            Column(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                horizontalAlignment = Alignment.End,
                            ) {
                                // The decorative mini-grid that used to sit behind this label is
                                // gone -- the real grid now spans the canvas, so the legend only
                                // needs an opaque backdrop to stay readable on top of it.
                                Column(
                                    modifier =
                                        Modifier
                                            .background(
                                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                shape = MaterialTheme.shapes.small,
                                            ).padding(horizontal = 6.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.End,
                                ) {
                                    Text(
                                        text =
                                            stringResource(
                                                R.string.workout_route_grid_legend,
                                                uiState.scaleLabel,
                                            ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    // Exactly one grid cell wide, so the label, the bar and a
                                    // square on the map all state the same distance.
                                    Box(
                                        modifier =
                                            Modifier
                                                .width(contourSideDp * uiState.scaleWidthFraction)
                                                .height(3.dp)
                                                .padding(top = 1.dp),
                                    ) {
                                        Canvas(Modifier.fillMaxSize()) {
                                            drawLine(
                                                color = routeColor,
                                                start = Offset(0f, size.height / 2),
                                                end = Offset(size.width, size.height / 2),
                                                strokeWidth = 2.dp.toPx(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                RouteDataState.NotAvailable -> Unit
            }
        }
    }
}
