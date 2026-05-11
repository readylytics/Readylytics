package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.PaiWeeklyBar
import com.gregor.lauritz.healthdashboard.ui.components.SectionHeader
import com.gregor.lauritz.healthdashboard.ui.components.StatusLegend
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.components.containerColor
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import com.gregor.lauritz.healthdashboard.ui.dashboard.DateSwitcher
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberEnd
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.Axis
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun WorkoutsRoute(
    viewModel: WorkoutsViewModel = hiltViewModel(),
    onWorkoutClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WorkoutsScreen(
        uiState = uiState,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onWorkoutClick = onWorkoutClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsScreen(
    uiState: WorkoutsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onWorkoutClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Explicitly persist scroll state across navigation
    rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
        listState
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item(key = "date_switcher") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "hero_section") {
            HeroSection(
                uiState = uiState,
                onRangeSelected = onRangeSelected,
            )
        }

        item(key = "history_header") {
            SectionHeader(title = "History")
        }

        items(uiState.recentWorkouts, key = { it.id }) { workout ->
            WorkoutHistoryItem(
                workout = workout,
                onClick = { onWorkoutClick(workout.id) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        item(key = "status_legend") {
            StatusLegend()
        }
    }
}

@Composable
private fun HeroSection(
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

            val strainTooltip = remember {
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
            modifier = Modifier
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
                        val paiTooltip = remember {
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
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
                override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
                override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = (rangeDays - 1).toDouble()

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
                override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
                override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = (rangeDays - 1).toDouble()

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
                        y = validTrimp.mapNotNull { it.value },
                    )
                }
            }
            val validRatio = ratioPoints.filter { it.value != null }
            if (validRatio.isNotEmpty()) {
                lineSeries {
                    series(
                        x = validRatio.map { it.dayOffset },
                        y = validRatio.mapNotNull { it.value },
                    )
                }
            }
        }
    }

    val dotComponent = rememberShapeComponent(fill = fill(ratioColor), shape = CorneredShape.Pill)
    val ratioLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(fill(ratioColor)),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.point(dotComponent, 6.dp),
                ),
        )

    val trimpColumn = rememberColumnCartesianLayer(
        columnProvider = ColumnCartesianLayer.ColumnProvider.series(
            LineComponent(
                fill = fill(trimpColor),
                thicknessDp = 8f,
                shape = CorneredShape.Pill,
            )
        ),
        rangeProvider = trimpRangeProvider,
        verticalAxisPosition = Axis.Position.Vertical.Start,
    )

    val trimpAxisItemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) }
    val ratioAxisItemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) }

    CartesianChartHost(
        chart =
            rememberCartesianChart(
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
                        title = "TRIMP",
                        itemPlacer = trimpAxisItemPlacer,
                        guideline = guidelineComponent,
                    ),
                endAxis =
                    VerticalAxis.rememberEnd(
                        label = labelComponent,
                        valueFormatter = ratioAxisFormatter,
                        titleComponent = axisLabelComponent,
                        title = "Strain",
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


@Composable
private fun WorkoutHistoryItem(
    workout: WorkoutRecordEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayType = exerciseTypeToDisplayName(workout.exerciseType)
    val dateStr = remember(workout.startTime) {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("(dd.MM)", Locale.getDefault())
        java.time.Instant.ofEpochMilli(workout.startTime)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .format(fmt)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$displayType $dateStr",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${workout.durationMinutes} min  ·  TRIMP ${workout.trimp.roundToInt()}  ·  ${if (workout.avgHr > 0) "${workout.avgHr} bpm" else "-- bpm"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IntensityBadge(
                label = workout.intensityLabel(),
                status = workout.intensityStatus(),
            )
        }
    }
}

@Composable
private fun IntensityBadge(
    label: String,
    status: MetricStatus,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = status.containerColor(),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = status.onContainerColor(),
        )
    }
}

private fun WorkoutRecordEntity.intensityLabel(): String =
    when {
        trimp > 200 -> "Very Hard"
        trimp > 150 -> "Hard"
        trimp > 100 -> "Moderate"
        trimp > 50 -> "Light"
        else -> "Very Light"
    }

private fun WorkoutRecordEntity.intensityStatus(): MetricStatus =
    when {
        trimp > 200 -> MetricStatus.POOR
        trimp > 150 -> MetricStatus.WARNING
        trimp > 50 -> MetricStatus.OPTIMAL
        else -> MetricStatus.CALIBRATING
    }
