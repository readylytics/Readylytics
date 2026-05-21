package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.DataPointTooltip
import com.gregor.lauritz.healthdashboard.ui.components.DataPointTooltipData
import com.gregor.lauritz.healthdashboard.ui.components.VicoChartTooltipOverlay
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import kotlin.math.roundToInt

private const val TARGET_X_AXIS_LABELS = 6

@Composable
fun TrimpBreakdownChart(
    chartData: List<Pair<Double, Double>>,
    durationMinutes: Int,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Heart Rate", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(16.dp))
            if (chartData.isEmpty()) {
                Text("No HR data available")
            } else {
                HrChart(chartData, durationMinutes)
            }
        }
    }
}

@Composable
private fun HrChart(
    chartData: List<Pair<Double, Double>>,
    durationMinutes: Int,
) {
    var tooltipState by remember { mutableStateOf<DataPointTooltipData?>(null) }
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(chartData) {
        if (chartData.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = chartData.map { it.first },
                    y = chartData.map { it.second },
                )
            }
        }
    }

    val labelMinutes =
        remember(durationMinutes) {
            computeLabelMinutes(durationMinutes, TARGET_X_AXIS_LABELS)
        }

    val itemPlacer =
        remember(labelMinutes) {
            val base =
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { 1 },
                    addExtremeLabelPadding = true,
                )
            object : HorizontalAxis.ItemPlacer by base {
                override fun getLabelValues(
                    context: CartesianDrawingContext,
                    visibleXRange: ClosedFloatingPointRange<Double>,
                    fullXRange: ClosedFloatingPointRange<Double>,
                    maxLabelWidth: Float,
                ): List<Double> = labelMinutes.filter { it in fullXRange }
            }
        }

    val rangeProvider =
        remember(chartData) {
            val values = chartData.map { it.second }
            val lo = values.minOrNull() ?: 0.0
            val hi = values.maxOrNull() ?: 0.0
            CartesianLayerRangeProvider.fixed(
                minY = (lo - 5.0).coerceAtLeast(0.0),
                maxY = hi + 5.0,
            )
        }

    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

    // Convert chart data to DailyDataPoint format for tooltip overlay
    val dailyPoints = remember(chartData) {
        chartData.map { (minute, bpm) ->
            DailyDataPoint(dayOffset = minute.toInt(), value = bpm.toFloat())
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        CartesianChartHost(
            chart =
                rememberCartesianChart(
                    rememberLineCartesianLayer(
                        lineProvider =
                            LineCartesianLayer.LineProvider.series(
                                LineCartesianLayer.rememberLine(
                                    fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.primary)),
                                ),
                            ),
                        rangeProvider = rangeProvider,
                    ),
                    startAxis =
                        VerticalAxis.rememberStart(
                            label = labelComponent,
                            title = { "bpm" },
                            titleComponent = axisLabelComponent,
                            guideline = guidelineComponent,
                            valueFormatter = CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() },
                        ),
                    bottomAxis =
                        HorizontalAxis.rememberBottom(
                            label = labelComponent,
                            title = { "Minutes" },
                            titleComponent = axisLabelComponent,
                            guideline = guidelineComponent,
                            valueFormatter = CartesianValueFormatter { _, value, _ -> value.roundToInt().toString() },
                            itemPlacer = itemPlacer,
                        ),
                ),
            modelProducer = modelProducer,
            zoomState =
                rememberVicoZoomState(
                    zoomEnabled = false,
                    initialZoom = Zoom.Content,
                ),
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )

        VicoChartTooltipOverlay(
            points = dailyPoints,
            rangeDays = durationMinutes,
            onDataPointSelected = { dayOffset, value ->
                val minute = dayOffset.coerceIn(0, durationMinutes - 1)
                val valueText = "HR: ${value.toInt()} bpm"
                val dateText = "Time: $minute min"
                tooltipState =
                    DataPointTooltipData(
                        valueText = valueText,
                        dateText = dateText,
                    )
            },
            modifier = Modifier.fillMaxWidth().height(200.dp),
        )
    }

    if (tooltipState != null) {
        DataPointTooltip(
            isVisible = true,
            data = tooltipState!!,
            onDismissRequest = { tooltipState = null },
        )
    }
}

private fun computeLabelMinutes(
    durationMinutes: Int,
    target: Int,
): List<Double> {
    if (durationMinutes <= 0) return listOf(0.0)
    val intervals = (target - 1).coerceAtLeast(1)
    if (durationMinutes <= intervals) {
        return (0..durationMinutes).map { it.toDouble() }
    }
    val step = durationMinutes.toDouble() / intervals
    return (0..intervals)
        .map { (it * step).roundToInt().toDouble() }
        .distinct()
}
