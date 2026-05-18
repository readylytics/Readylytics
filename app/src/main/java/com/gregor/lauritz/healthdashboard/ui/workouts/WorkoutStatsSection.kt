package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.PaiWeeklyBar
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.columnSeries
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutStatsSection(
    uiState: WorkoutsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val strainRatio = uiState.latestSummary?.strainRatio
            val strainStatus =
                when {
                    strainRatio == null -> MetricStatus.CALIBRATING
                    strainRatio in 0.8f..1.3f -> MetricStatus.OPTIMAL
                    strainRatio in 1.3f..1.5f -> MetricStatus.NEUTRAL
                    strainRatio > 1.5f -> MetricStatus.POOR
                    else -> MetricStatus.WARNING
                }

            val strainTooltip =
                remember {
                    buildString {
                        append("The ACWR (Acute:Chronic Workload Ratio).\n\n")
                        append("• 0.8–1.3: Optimal range\n")
                        append("• > 1.5: High injury risk\n")
                        append("• < 0.8: Detraining risk")
                    }
                }
            M3ScoreDial(
                score = strainRatio,
                label = "Strain Ratio",
                maxScore = 2.0f,
                status = strainStatus,
                displayText = strainRatio?.let { "%.2f".format(it) } ?: "—",
                tooltipDescription = strainTooltip,
            )
            M3ScoreDial(
                score = uiState.latestSummary?.readinessScore,
                label = "Readiness",
                tooltipDescription = "Physical preparedness for strain today.",
            )
        }

        Spacer(Modifier.height(8.dp))

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("PAI", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        uiState.todayPaiScore?.let { earned ->
                            if (earned > 0f) {
                                Text(
                                    text = "+${earned.toInt()} today",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        val paiTooltip =
                            remember {
                                buildString {
                                    append("Your 7-day rolling heart health score.\n")
                                    append("Based on how often and how hard you challenge your heart.\n\n")
                                    append("• 100+: Optimal\n")
                                    append("• 75–99: Neutral\n")
                                    append("• 50–74: Warning\n")
                                    append("• < 50: Poor")
                                }
                            }
                        MetricTooltip(
                            description = paiTooltip,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                PaiWeeklyBar(
                    dailyBreakdown = uiState.paiDailyBreakdown,
                    totalPai = uiState.latestSummary?.totalPai ?: 0f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionHeader(title = "Training Load & Strain Ratio (ACWR)")
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        ) {
            TimeRange.entries.forEachIndexed { index, range ->
                SegmentedButton(
                    selected = uiState.selectedRange == range,
                    onClick = { onRangeSelected(range) },
                    shape =
                        SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = TimeRange.entries.size,
                        ),
                    label = { Text(range.label) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        AcwrChartCard(
            trimpPoints = uiState.dailyTrimp,
            ratioPoints = uiState.dailyStrainRatio,
            rangeStartMs = uiState.rangeStartMs,
            rangeDays = uiState.selectedRange.days,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AcwrChartCard(
    trimpPoints: List<DailyDataPoint>,
    ratioPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Daily TRIMP",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Strain Ratio →",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (trimpPoints.isEmpty() && ratioPoints.isEmpty()) {
                EmptyChartPlaceholder()
            } else {
                AcwrChart(
                    trimpPoints = trimpPoints,
                    ratioPoints = ratioPoints,
                    rangeStartMs = rangeStartMs,
                    rangeDays = rangeDays,
                )
            }
        }
    }
}

@Composable
private fun AcwrChart(
    trimpPoints: List<DailyDataPoint>,
    ratioPoints: List<DailyDataPoint>,
    rangeStartMs: Long,
    rangeDays: Int,
    modifier: Modifier = Modifier,
) {
    val ratioColor = MaterialTheme.colorScheme.primary
    val trimpColor = MaterialTheme.colorScheme.outline
    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()
    val trimpAxisFormatter = remember { CartesianValueFormatter { _, value, _ -> value.toInt().toString() } }
    val ratioAxisFormatter = remember { CartesianValueFormatter { _, value, _ -> "%.2f".format(value) } }

    val modelProducer = remember { CartesianChartModelProducer() }

    val trimpRangeProvider =
        remember(trimpPoints, rangeDays) {
            object : CartesianLayerRangeProvider {
                override fun getMinX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = 0.0

                override fun getMaxX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = (rangeDays - 1).toDouble()

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = (ceil(maxY / 25.0) * 25.0).coerceAtLeast(100.0)

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = 0.0
            }
        }

    val ratioRangeProvider =
        remember(ratioPoints, rangeDays) {
            object : CartesianLayerRangeProvider {
                override fun getMinX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = 0.0

                override fun getMaxX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ) = (rangeDays - 1).toDouble()

                override fun getMaxY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = (ceil(maxY / 0.5) * 0.5).coerceAtLeast(2.0)

                override fun getMinY(
                    minY: Double,
                    maxY: Double,
                    extraStore: ExtraStore,
                ): Double = 0.0
            }
        }

    val xAxisFormatter = ChartDefaults.rememberDayOffsetFormatter(rangeStartMs)

    LaunchedEffect(trimpPoints, ratioPoints) {
        modelProducer.runTransaction {
            val validTrimp = trimpPoints.filter { it.value != null }
            if (validTrimp.isNotEmpty()) {
                columnSeries {
                    series(
                        x = validTrimp.map { it.dayOffset },
                        y = validTrimp.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
            val validRatio = ratioPoints.filter { it.value != null }
            if (validRatio.isNotEmpty()) {
                lineSeries {
                    series(
                        x = validRatio.map { it.dayOffset },
                        y = validRatio.mapNotNull { it.value?.toDouble() },
                    )
                }
            }
        }
    }

    val dotComponent = rememberShapeComponent(fill = Fill(ratioColor), shape = CircleShape)
    val ratioLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(Fill(ratioColor)),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.Point(dotComponent, 6.dp),
                ),
        )

    val trimpColumn =
        rememberColumnCartesianLayer(
            columnProvider =
                ColumnCartesianLayer.ColumnProvider.series(
                    rememberLineComponent(
                        fill = Fill(trimpColor),
                        thickness = 8.dp,
                        shape = CircleShape,
                    ),
                ),
            rangeProvider = trimpRangeProvider,
            verticalAxisPosition = Axis.Position.Vertical.Start,
        )

    val trimpAxisItemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) }
    val ratioAxisItemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) }

    CartesianChartHost(
        chart =
            com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart(
                trimpColumn,
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(ratioLine),
                    rangeProvider = ratioRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.End,
                ),
                startAxis =
                    VerticalAxis.rememberStart(
                        label = labelComponent,
                        valueFormatter = trimpAxisFormatter,
                        titleComponent = axisLabelComponent,
                        title = { "TRIMP" },
                        itemPlacer = trimpAxisItemPlacer,
                        guideline = guidelineComponent,
                    ),
                endAxis =
                    VerticalAxis.rememberEnd(
                        label = labelComponent,
                        valueFormatter = ratioAxisFormatter,
                        titleComponent = axisLabelComponent,
                        title = { "Strain" },
                        itemPlacer = ratioAxisItemPlacer,
                        guideline = null,
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        label = labelComponent,
                        valueFormatter = xAxisFormatter,
                        itemPlacer = remember(rangeDays) { ChartDefaults.itemPlacerForRangeDays(rangeDays) },
                        guideline = guidelineComponent,
                    ),
            ),
        modelProducer = modelProducer,
        modifier = modifier.fillMaxWidth().height(220.dp),
    )
}

@Composable
private fun EmptyChartPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Not enough data",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
