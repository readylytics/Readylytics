package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.InvisibleMarker
import app.readylytics.health.core.ui.components.rememberChartMarkerVisibilityListener
import com.patrykandpatrick.vico.compose.cartesian.CartesianChart
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill

internal val DistanceChartHeight = 200.dp

private const val MIN_X_SPAN = 0.1
private const val X_STEP = 0.1
private const val LINE_CURVATURE = 0.2f

@Composable
internal fun DistanceSeriesCartesianChart(
    points: List<Pair<Double, Double>>,
    kind: DistanceSeriesKind,
    units: DistanceSeriesUnits,
    lineColor: Color,
    selection: DistanceChartSelection,
) {
    CartesianChartHost(
        chart = rememberDistanceCartesianChart(points, kind, units, lineColor, selection),
        modelProducer = rememberDistanceSeriesModel(points),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
        modifier = Modifier.fillMaxWidth().height(DistanceChartHeight),
    )
}

@Composable
private fun rememberDistanceSeriesModel(points: List<Pair<Double, Double>>): CartesianChartModelProducer {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        if (points.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineModel {
                series(
                    x = points.map { it.first },
                    y = points.map { it.second },
                )
            }
        }
    }
    return modelProducer
}

@Composable
private fun rememberDistanceCartesianChart(
    points: List<Pair<Double, Double>>,
    kind: DistanceSeriesKind,
    units: DistanceSeriesUnits,
    lineColor: Color,
    selection: DistanceChartSelection,
): CartesianChart {
    val maxDistance = points.lastOrNull()?.first ?: 0.0
    val markerVisibilityListener =
        rememberChartMarkerVisibilityListener(
            onPointSelected = { x, _, canvasX, canvasY ->
                selection.pointOffset = Offset(canvasX, canvasY)
                selection.index = nearestPointIndex(points, x)
            },
        )
    return rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(rememberSeriesLine(lineColor, kind.areaAlpha)),
            rangeProvider = rememberDistanceRangeProvider(points, kind, maxDistance),
        ),
        startAxis = rememberDistanceStartAxis(kind, units),
        bottomAxis = rememberDistanceBottomAxis(units, maxDistance),
        marker = InvisibleMarker,
        markerVisibilityListener = markerVisibilityListener,
        getXStep = { _, _, _ -> X_STEP },
    )
}

@Composable
private fun rememberDistanceRangeProvider(
    points: List<Pair<Double, Double>>,
    kind: DistanceSeriesKind,
    maxDistance: Double,
): CartesianLayerRangeProvider =
    remember(points, kind, maxDistance) {
        val yRange = seriesYRange(points.map { it.second }, kind.yMargin)
        CartesianLayerRangeProvider.fixed(
            minX = 0.0,
            maxX = maxDistance.coerceAtLeast(MIN_X_SPAN),
            minY = yRange.start,
            maxY = yRange.endInclusive,
        )
    }

@Composable
private fun rememberSeriesLine(
    color: Color,
    areaAlpha: Float,
) = LineCartesianLayer.rememberLine(
    fill = LineCartesianLayer.LineFill.single(Fill(color)),
    areaFill =
        LineCartesianLayer.AreaFill.single(
            Fill(
                brush =
                    Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = areaAlpha), color.copy(alpha = 0.0f)),
                    ),
            ),
        ),
    interpolator = LineCartesianLayer.Interpolator.cubic(LINE_CURVATURE),
)

@Composable
private fun rememberDistanceStartAxis(
    kind: DistanceSeriesKind,
    units: DistanceSeriesUnits,
) = VerticalAxis.rememberStart(
    label = ChartDefaults.labelTextComponent(),
    title = { units.value },
    titleComponent = ChartDefaults.axisLabelTextComponent(),
    guideline = ChartDefaults.guidelineComponent(),
    valueFormatter = CartesianValueFormatter { _, value, _ -> formatSeriesValue(kind, value) },
)

@Composable
private fun rememberDistanceBottomAxis(
    units: DistanceSeriesUnits,
    maxDistance: Double,
) = HorizontalAxis.rememberBottom(
    label = ChartDefaults.labelTextComponent(),
    title = { units.distance },
    titleComponent = ChartDefaults.axisLabelTextComponent(),
    guideline = ChartDefaults.guidelineComponent(),
    valueFormatter = CartesianValueFormatter { _, value, _ -> formatDistanceTick(value, maxDistance) },
    itemPlacer = rememberDistanceItemPlacer(maxDistance),
)

@Composable
private fun rememberDistanceItemPlacer(maxDistance: Double): HorizontalAxis.ItemPlacer {
    val labels = remember(maxDistance) { computeDistanceLabels(maxDistance) }
    return remember(labels) {
        val base = HorizontalAxis.ItemPlacer.aligned(spacing = { 1 }, addExtremeLabelPadding = true)
        object : HorizontalAxis.ItemPlacer by base {
            override fun getLabelValues(
                context: CartesianDrawingContext,
                visibleXRange: ClosedFloatingPointRange<Double>,
                fullXRange: ClosedFloatingPointRange<Double>,
                maxLabelWidth: Float,
            ): List<Double> = labels.filter { it in fullXRange }
        }
    }
}
