package app.readylytics.health.feature.workouts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.domain.util.ProjectedPoint

enum class RouteDataState {
    Available,
    PermissionRequired,
    NotAvailable,
}

data class RouteUiState(
    val state: RouteDataState = RouteDataState.NotAvailable,
    val projectedPoints: List<ProjectedPoint> = emptyList(),
    val scaleLabel: String = "",
    val scaleWidthDp: Float = 0f,
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
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1.4f)
                                .padding(MaterialTheme.spacing.small),
                    ) {
                        val routeColor = MaterialTheme.colorScheme.primary
                        val startColor = Color(0xFF4CAF50)
                        val endColor = MaterialTheme.colorScheme.error

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val points = uiState.projectedPoints
                            if (points.size < 2) return@Canvas

                            val paddingPx = 16.dp.toPx()
                            val drawWidth = size.width - 2 * paddingPx
                            val drawHeight = size.height - 2 * paddingPx

                            val path = Path()
                            points.forEachIndexed { i, p ->
                                val px = paddingPx + p.x * drawWidth
                                val py = paddingPx + p.y * drawHeight
                                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
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
                            val startP = points.first()
                            drawCircle(
                                color = startColor,
                                radius = 5.dp.toPx(),
                                center = Offset(paddingPx + startP.x * drawWidth, paddingPx + startP.y * drawHeight),
                            )

                            // End point dot
                            val endP = points.last()
                            drawCircle(
                                color = endColor,
                                radius = 5.dp.toPx(),
                                center = Offset(paddingPx + endP.x * drawWidth, paddingPx + endP.y * drawHeight),
                            )
                        }

                        // Scale bar
                        if (uiState.scaleLabel.isNotEmpty() && uiState.scaleWidthDp > 0f) {
                            Column(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp),
                                horizontalAlignment = Alignment.End,
                            ) {
                                Box {
                                    val gridColor =
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
                                    Canvas(modifier = Modifier.matchParentSize()) {
                                        val cellSize = 10.dp.toPx()
                                        var x = 0f
                                        while (x <= size.width) {
                                            drawLine(
                                                color = gridColor,
                                                start = Offset(x, 0f),
                                                end = Offset(x, size.height),
                                                strokeWidth = 1f,
                                            )
                                            x += cellSize
                                        }
                                        var y = 0f
                                        while (y <= size.height) {
                                            drawLine(
                                                color = gridColor,
                                                start = Offset(0f, y),
                                                end = Offset(size.width, y),
                                                strokeWidth = 1f,
                                            )
                                            y += cellSize
                                        }
                                    }
                                    Column(
                                        modifier =
                                            Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                        horizontalAlignment = Alignment.End,
                                    ) {
                                        Text(
                                            text = uiState.scaleLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Box(
                                            modifier =
                                                Modifier
                                                    .width(uiState.scaleWidthDp.dp.coerceIn(30.dp, 100.dp))
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
                }
                RouteDataState.NotAvailable -> Unit
            }
        }
    }
}
