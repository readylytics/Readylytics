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
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@Composable
fun WorkoutsRoute(viewModel: WorkoutsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WorkoutsScreen(uiState = uiState, onRangeSelected = viewModel::onRangeSelected)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsScreen(
    uiState: WorkoutsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            HeroSection(uiState = uiState)
        }

        item { Spacer(Modifier.height(24.dp)) }

        item {
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
        }

        item { Spacer(Modifier.height(8.dp)) }

        item {
            AcwrChartCard(
                trimpPoints = uiState.dailyTrimp,
                ratioPoints = uiState.dailyStrainRatio,
                rangeStartMs = uiState.rangeStartMs,
                rangeDays = uiState.selectedRange.days,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item { Spacer(Modifier.height(24.dp)) }

        item {
            SectionHeader(title = "Workout History")
            Spacer(Modifier.height(8.dp))
        }

        if (uiState.recentWorkouts.isEmpty()) {
            item {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No workouts in this period",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(uiState.recentWorkouts) { workout ->
                WorkoutHistoryItem(
                    workout = workout,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun HeroSection(
    uiState: WorkoutsUiState,
    modifier: Modifier = Modifier,
) {
    val strainRatio = uiState.latestSummary?.strainRatio
    val strainScore = strainRatioToScore(strainRatio)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        M3ScoreDial(
            score = uiState.latestSummary?.loadScore,
            label = "Strain Ratio",
            displayText = uiState.latestSummary?.strainRatio?.let { "%.2f".format(it) },
            tooltipDescription =
                buildString {
                    append("Short-term fatigue vs. long-term fitness.\n\n")
                    append("• 0.8–1.2: Sweet spot for fitness gains\n")
                    append("• < 0.8: Under-training\n")
                    append("• > 1.2: Increasing injury risk")
                },
        )
        M3ScoreDial(
            score = uiState.latestSummary?.readinessScore,
            label = "Readiness",
            tooltipDescription =
                buildString {
                    append("Preparation for stress based on recent load & recovery.\n\n")
                    append("• 85–100: Peak\n")
                    append("• 30–69: Moderate\n")
                    append("• < 30: Rest")
                },
        )
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
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Daily TRIMP", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Strain Ratio →",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (trimpPoints.isEmpty()) {
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
    val trimpTitleComponent = rememberTextComponent(color = axisLabelColor)
    val strainTitleComponent = rememberTextComponent(color = axisLabelColor)
    val trimpAxisFormatter = remember { CartesianValueFormatter { _, value, _ -> value.toInt().toString() } }
    val ratioAxisFormatter = remember { CartesianValueFormatter { _, value, _ -> "%.2f".format(value) } }

    val trimpMaxY =
        remember(trimpPoints) {
            (trimpPoints.maxOfOrNull { it.value }?.let { it * 1.1f } ?: 100f).toDouble()
        }
    val ratioMinY =
        remember(ratioPoints) {
            (ratioPoints.minOfOrNull { it.value }?.let { it * 0.9f } ?: 0f).toDouble()
        }
    val ratioMaxY =
        remember(ratioPoints) {
            (ratioPoints.maxOfOrNull { it.value }?.let { it * 1.1f } ?: 2f).toDouble()
        }

    val modelProducer = remember { CartesianChartModelProducer() }

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

    val trimpRangeProvider =
        remember(trimpMaxY) { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = trimpMaxY) }
    val ratioRangeProvider =
        remember(ratioMinY, ratioMaxY) { CartesianLayerRangeProvider.fixed(minY = ratioMinY, maxY = ratioMaxY) }
    val dotComponent = rememberShapeComponent(fill = fill(dotColor), shape = CorneredShape.Pill)
    val ratioLine =
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
                        valueFormatter = trimpAxisFormatter,
                        titleComponent = trimpTitleComponent,
                        title = "TRIMP",
                    ),
                endAxis =
                    VerticalAxis.rememberEnd(
                        valueFormatter = ratioAxisFormatter,
                        titleComponent = strainTitleComponent,
                        title = "Strain",
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        valueFormatter = xAxisFormatter,
                        itemPlacer = remember(rangeDays) { HorizontalAxis.ItemPlacer.segmented() },
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
    val status = workout.intensityStatus()
    val dateFormatter = remember { SimpleDateFormat(DateFormatUtils.DATE_FORMAT_SHORT, Locale.getDefault()) }
    val workoutDate = remember(workout.startTime) { dateFormatter.format(Date(workout.startTime)) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${exerciseTypeToDisplayName(workout.exerciseType)} ($workoutDate)",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${workout.durationMinutes} min  ·  TRIMP ${workout.trimp.roundToInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IntensityBadge(label = workout.intensityLabel(), status = status)
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
        shape = RoundedCornerShape(50),
        color = status.containerColor(),
        contentColor = status.onContainerColor(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun strainRatioToScore(ratio: Float?): Float? {
    if (ratio == null) return null
    return when {
        ratio < 0.8f -> 80f
        ratio <= 1.2f -> 100f
        ratio <= 1.5f -> 100f - (ratio - 1.2f) * 200f
        else -> 40f
    }
}

private fun exerciseTypeToDisplayName(typeString: String): String {
    val type = typeString.toIntOrNull() ?: return "Workout"
    return when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "Workout"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "Badminton"
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL -> "Baseball"
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "Basketball"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Stationary Bike"
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> "Boot Camp"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "Boxing"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Calisthenics"
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "Cricket"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Dancing"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "Exercise Class"
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING -> "Fencing"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> "American Football"
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN -> "Australian Football"
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC -> "Frisbee"
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "Golf"
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> "Guided Breathing"
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> "Gymnastics"
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL -> "Handball"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY -> "Ice Hockey"
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> "Ice Skating"
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "Martial Arts"
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> "Paddling"
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING -> "Paragliding"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL -> "Racquetball"
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "Rock Climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY -> "Roller Hockey"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "Rowing"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rowing Machine"
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> "Rugby"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill Run"
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING -> "Sailing"
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING -> "Scuba Diving"
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> "Skating"
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "Skiing"
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "Snowboarding"
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> "Snowshoeing"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Soccer"
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL -> "Softball"
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> "Squash"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "Stair Climbing"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Stair Machine"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength Training"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "Stretching"
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> "Surfing"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Open Water Swim"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Pool Swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "Table Tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "Volleyball"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO -> "Water Polo"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Weightlifting"
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> "Wheelchair"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        else -> "Workout"
    }
}

private fun WorkoutRecordEntity.intensityLabel(): String =
    when {
        trimp < 40f -> "Easy"
        trimp < 80f -> "Moderate"
        trimp < 120f -> "Hard"
        else -> "Very Hard"
    }

private fun WorkoutRecordEntity.intensityStatus(): MetricStatus =
    when {
        trimp < 40f -> MetricStatus.OPTIMAL
        trimp < 80f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
