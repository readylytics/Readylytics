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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.ui.common.DailyDataPoint
import com.gregor.lauritz.healthdashboard.ui.common.DateFormatUtils
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
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
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.component.LineComponent
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun WorkoutsRoute(viewModel: WorkoutsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WorkoutsScreen(
        uiState = uiState,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsScreen(
    uiState: WorkoutsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            DateSwitcher(
                selectedDate = uiState.selectedDate,
                onPreviousDay = onPreviousDay,
                onNextDay = onNextDay,
            )
        }

        item {
            HeroSection(
                uiState = uiState,
                onRangeSelected = onRangeSelected,
            )
        }

        item {
            SectionHeader(title = "History")
        }

        items(uiState.recentWorkouts) { workout ->
            WorkoutHistoryItem(
                workout = workout,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
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

            M3ScoreDial(
                score = strainRatio,
                label = "Strain Ratio",
                maxScore = 2.0f,
                status = strainStatus,
                displayText = strainRatio?.let { "%.2f".format(it) },
                tooltipDescription =
                    buildString {
                        append("The ACWR (Acute:Chronic Workload Ratio).\n\n")
                        append("• 0.8–1.3: Optimal range\n")
                        append("• > 1.5: High injury risk\n")
                        append("• < 0.8: Detraining risk")
                    },
            )
            M3ScoreDial(
                score = uiState.latestSummary?.readinessScore,
                label = "Readiness",
                tooltipDescription = "Physical preparedness for strain today.",
            )
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
    val dayMs = TimeUnit.DAYS.toMillis(1)
    val dotColor = MaterialTheme.colorScheme.primary
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val guidelineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val labelComponent = rememberTextComponent(color = labelColor)
    val trimpTitleComponent = rememberTextComponent(color = axisLabelColor)
    val strainTitleComponent = rememberTextComponent(color = axisLabelColor)
    val trimpAxisFormatter = remember { CartesianValueFormatter { _, value, _ -> value.toInt().toString() } }
    val ratioAxisFormatter = remember { CartesianValueFormatter { _, value, _ -> "%.2f".format(value) } }

    val modelProducer = remember { CartesianChartModelProducer() }

    val trimpRangeProvider =
        remember(trimpPoints) {
            object : CartesianLayerRangeProvider {
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
        remember(ratioPoints) {
            object : CartesianLayerRangeProvider {
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

    val dateFormatter =
        remember(rangeStartMs) { SimpleDateFormat(DateFormatUtils.DATE_FORMAT_SHORT, Locale.getDefault()) }
    val xAxisFormatter =
        remember(rangeStartMs) {
            CartesianValueFormatter { _, value, _ ->
                dateFormatter.format(Date(rangeStartMs + value.toLong() * dayMs))
            }
        }

    LaunchedEffect(trimpPoints, ratioPoints) {
        modelProducer.runTransaction {
            columnSeries { series(x = trimpPoints.map { it.dayOffset }, y = trimpPoints.map { it.value }) }
            if (ratioPoints.isNotEmpty()) {
                lineSeries { series(x = ratioPoints.map { it.dayOffset }, y = ratioPoints.map { it.value }) }
            }
        }
    }

    val dotComponent = rememberShapeComponent(fill = fill(dotColor), shape = CorneredShape.Pill)
    val ratioLine =
        LineCartesianLayer.rememberLine(
            fill = LineCartesianLayer.LineFill.single(fill(dotColor)),
            pointProvider =
                LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.point(dotComponent, 6.dp),
                ),
        )

    val trimpAxisItemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) }
    val ratioAxisItemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) }

    CartesianChartHost(
        chart =
            rememberCartesianChart(
                rememberColumnCartesianLayer(
                    rangeProvider = trimpRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.Start,
                ),
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(ratioLine),
                    rangeProvider = ratioRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.End,
                ),
                startAxis =
                    VerticalAxis.rememberStart(
                        label = labelComponent,
                        valueFormatter = trimpAxisFormatter,
                        titleComponent = trimpTitleComponent,
                        title = "TRIMP",
                        itemPlacer = trimpAxisItemPlacer,
                        guideline = LineComponent(fill = fill(guidelineColor), thicknessDp = 1f),
                    ),
                endAxis =
                    VerticalAxis.rememberEnd(
                        label = labelComponent,
                        valueFormatter = ratioAxisFormatter,
                        titleComponent = strainTitleComponent,
                        title = "Strain",
                        itemPlacer = ratioAxisItemPlacer,
                        guideline = null,
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        label = labelComponent,
                        valueFormatter = xAxisFormatter,
                        itemPlacer =
                            remember(rangeDays) {
                                if (rangeDays == 7) {
                                    HorizontalAxis.ItemPlacer.aligned(
                                        spacing = { 2 },
                                        addExtremeLabelPadding = true,
                                    )
                                } else {
                                    HorizontalAxis.ItemPlacer.aligned(
                                        spacing = { 5 },
                                        addExtremeLabelPadding = true,
                                    )
                                }
                            },
                        guideline = LineComponent(fill = fill(guidelineColor), thicknessDp = 1f),
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
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun WorkoutHistoryItem(
    workout: WorkoutRecordEntity,
    modifier: Modifier = Modifier,
) {
    val displayType = exerciseTypeToDisplayName(workout.exerciseType)
    val dateStr = SimpleDateFormat("(dd.MM)", Locale.getDefault()).format(Date(workout.startTime))

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
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
                    text = "${workout.durationMinutes} min  ·  TRIMP ${workout.trimp.roundToInt()}",
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

private fun exerciseTypeToDisplayName(type: String): String =
    when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING.toString() -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING.toString() -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING.toString() -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL.toString(),
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER.toString(),
        -> "Swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING.toString() -> "Strength"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING.toString() -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA.toString() -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES.toString() -> "Pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL.toString() -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE.toString() -> "Rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING.toString(),
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE.toString(),
        -> "Stairs"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING.toString() -> "HIIT"
        else ->
            type
                .replace("EXERCISE_TYPE_", "")
                .lowercase()
                .replaceFirstChar { it.uppercase() }
                .replace("_", " ")
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
        trimp > 150 -> MetricStatus.WARNING
        trimp > 50 -> MetricStatus.OPTIMAL
        else -> MetricStatus.CALIBRATING
    }
