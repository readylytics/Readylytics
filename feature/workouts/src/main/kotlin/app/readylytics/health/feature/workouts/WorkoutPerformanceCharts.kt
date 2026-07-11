package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.ChartDefaults
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
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
import com.patrykandpatrick.vico.compose.common.Fill
import java.util.Locale
import kotlin.math.abs

@Composable
fun WorkoutPerformanceChartCard(
    title: String,
    chartData: List<Pair<Float, Float>>,
    isInverted: Boolean,
    yAxisTitle: String,
    xAxisTitle: String,
    modifier: Modifier = Modifier,
) {
    if (chartData.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            WorkoutPerformanceChart(
                chartData = chartData,
                isInverted = isInverted,
                yAxisTitle = yAxisTitle,
                xAxisTitle = xAxisTitle,
            )
        }
    }
}

@Composable
fun WorkoutPerformanceChart(
    chartData: List<Pair<Float, Float>>,
    isInverted: Boolean,
    yAxisTitle: String,
    xAxisTitle: String,
    modifier: Modifier = Modifier,
) {
    if (chartData.isEmpty()) return

    val series = remember(chartData, isInverted) { performanceChartSeries(chartData, isInverted) }
    val modelProducer = remember { CartesianChartModelProducer() }
    val lineColor = MaterialTheme.colorScheme.primary
    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val axisNumberFormat = stringResource(R.string.workout_chart_axis_number_format)
    val rangeProvider = remember(series) { performanceChartRange(series) }
    val line =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(lineColor)),
            areaFill =
                LineCartesianLayer.AreaFill.single(
                    Fill(
                        brush =
                            Brush.verticalGradient(
                                colors = listOf(lineColor.copy(alpha = 0.32f), lineColor.copy(alpha = 0f)),
                            ),
                    ),
                ),
            interpolator = LineCartesianLayer.Interpolator.cubic(0.2f),
        )
    val valueFormatter =
        remember(axisNumberFormat, isInverted) {
            CartesianValueFormatter { _, value, _ ->
                formatChartAxisValue(axisNumberFormat, value, isInverted)
            }
        }

    LaunchedEffect(series) {
        modelProducer.runTransaction {
            lineModel {
                series(x = series.map { it.first }, y = series.map { it.second })
            }
        }
    }

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
                        title = { yAxisTitle },
                        titleComponent = axisLabelComponent,
                        guideline = guidelineComponent,
                        valueFormatter = valueFormatter,
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        label = labelComponent,
                        title = { xAxisTitle },
                        titleComponent = axisLabelComponent,
                        guideline = guidelineComponent,
                        valueFormatter =
                            CartesianValueFormatter { _, value, _ ->
                                formatChartAxisValue(axisNumberFormat, value, isInverted = false)
                            },
                    ),
            ),
        modelProducer = modelProducer,
        modifier = modifier.fillMaxWidth().height(220.dp),
    )
}

internal fun performanceChartSeries(
    chartData: List<Pair<Float, Float>>,
    isInverted: Boolean,
): List<Pair<Double, Double>> =
    chartData.map { (distance, value) ->
        distance.toDouble() to if (isInverted) -value.toDouble() else value.toDouble()
    }

internal fun formatChartAxisValue(
    format: String,
    value: Double,
    isInverted: Boolean,
    locale: Locale = Locale.getDefault(),
): String = String.format(locale, format, if (isInverted) -value else value)

private fun performanceChartRange(series: List<Pair<Double, Double>>): CartesianLayerRangeProvider {
    val xValues = series.map { it.first }
    val yValues = series.map { it.second }
    val minX = xValues.minOrNull() ?: 0.0
    val maxX = xValues.maxOrNull() ?: 1.0
    val minY = yValues.minOrNull() ?: 0.0
    val maxY = yValues.maxOrNull() ?: 1.0
    val yPadding = (abs(maxY - minY) * 0.1).coerceAtLeast(0.5)
    val xPadding = (abs(maxX - minX) * 0.02).coerceAtLeast(0.1)

    return CartesianLayerRangeProvider.fixed(
        minX = minX - xPadding,
        maxX = maxX + xPadding,
        minY = minY - yPadding,
        maxY = maxY + yPadding,
    )
}
