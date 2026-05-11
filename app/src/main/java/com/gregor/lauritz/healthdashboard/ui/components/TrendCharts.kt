package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalLine
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape

@Composable
fun TrendCard(
    title: String,
    unit: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
fun TrendChart(
    points: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    baselineUnit: String,
    baseline: Float? = null,
    showBaseline: Boolean = true,
    scrollState: VicoScrollState = rememberVicoScrollState(),
    modifier: Modifier = Modifier,
) {
    if (points.none { it.value != null }) {
        EmptyChartPlaceholder(modifier = modifier)
        return
    }

    val calculatedBaseline =
        remember(points) {
            val values = points.mapNotNull { it.value }.sorted()
            if (values.isEmpty()) return@remember 0f
            val mid = values.size / 2
            if (values.size % 2 == 0) (values[mid - 1] + values[mid]) / 2f else values[mid]
        }

    // Use provided baseline if available, otherwise fall back to calculated baseline
    val baselineValue = baseline ?: calculatedBaseline

    val (minY, maxY) =
        remember(points) {
            val values = points.mapNotNull { it.value }
            if (values.isEmpty()) return@remember 0.0 to 0.0
            val lo = values.minOrNull() ?: 0f
            val hi = values.maxOrNull() ?: 0f
            (lo * 0.9f).toDouble() to (hi * 1.1f).toDouble()
        }

    val labelComponent = ChartDefaults.labelTextComponent()
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val dotColor = MaterialTheme.colorScheme.primary

    val modelProducer = remember { CartesianChartModelProducer() }

    val xAxisFormatter = ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)

    LaunchedEffect(points) {
        modelProducer.runTransaction {
            val validPoints = points.filter { it.value != null }
            lineSeries {
                series(
                    x = validPoints.map { it.dayOffset },
                    y = validPoints.mapNotNull { it.value },
                )
            }
        }
    }

    // Note: Vico scroll positioning is handled through initial rememberVicoScrollState
    // Auto-scroll on range change would require accessing internal Vico APIs

    val rangeProvider = remember(minY, maxY, rangeDays) {
        CartesianLayerRangeProvider.fixed(
            minX = 0.0,
            maxX = (rangeDays - 1).toDouble(),
            minY = minY,
            maxY = maxY,
        )
    }
    val dotComponent = rememberShapeComponent(fill = fill(dotColor), shape = CorneredShape.Pill)
    val line =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(fill(dotColor)),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.point(dotComponent, 6.dp),
                ),
        )

    CartesianChartHost(
        chart =
            rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(line),
                    rangeProvider = rangeProvider,
                ),
                startAxis =
                    VerticalAxis.rememberStart(
                        label = labelComponent,
                        valueFormatter = CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() },
                        guideline = guidelineComponent,
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        label = labelComponent,
                        valueFormatter = xAxisFormatter,
                        itemPlacer = remember(rangeDays) { ChartDefaults.itemPlacerForRangeDays(rangeDays) },
                        guideline = guidelineComponent,
                    ),
                decorations =
                    if (showBaseline) listOf(
                        HorizontalLine(
                            y = { baselineValue.roundToInt().toDouble() },
                            line = LineComponent(fill = fill(baselineColor), thicknessDp = 1f),
                        ),
                    ) else emptyList(),
            ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        zoomState = rememberVicoZoomState(zoomEnabled = false),
        modifier = modifier.fillMaxWidth().height(180.dp),
    )

    if (showBaseline) {
        Spacer(Modifier.height(6.dp))
        BaselineLegend(
            value = baselineValue,
            unit = baselineUnit,
            color = baselineColor,
        )
    }
}

@Composable
fun BaselineLegend(
    value: Float,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 12.dp, height = 2.dp)
                    .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Baseline: ${value.roundToInt()} $unit",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
fun EmptyChartPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No data available",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
