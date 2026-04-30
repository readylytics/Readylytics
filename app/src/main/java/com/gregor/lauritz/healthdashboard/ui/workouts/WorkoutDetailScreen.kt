package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.common.DateFormatUtils
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.PaiWeeklyBar
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import java.time.Instant
import java.time.ZoneId
import com.gregor.lauritz.healthdashboard.ui.theme.LocalExtendedColors
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import kotlin.math.roundToInt

private const val TARGET_X_AXIS_LABELS = 6

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailRoute(
    workoutId: String,
    onBack: () -> Unit,
    viewModel: WorkoutDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(workoutId) {
        viewModel.loadWorkout(workoutId)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Workout Details") },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            WorkoutDetailScreen(
                uiState = uiState,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
fun WorkoutDetailScreen(
    uiState: WorkoutDetailUiState,
    modifier: Modifier = Modifier,
) {
    val workout = uiState.workout ?: return

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            WorkoutHeader(workout)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MetricCard(
                    title = "Training Load",
                    value = workout.trimp.roundToInt().toString(),
                    secondaryText = "TRIMP",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Total training impulse - measures training intensity and duration.",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    title = "Avg Pulse",
                    value = if (workout.avgHr > 0) workout.avgHr.toString() else "--",
                    secondaryText = "BPM",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Average heart rate during the workout.",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            ZoneBreakdownCard(workout)
        }

        item {
            HrChartCard(uiState.hrChartData, uiState.durationMinutes)
        }

        item {
            HrrCard(uiState)
        }
    }
}

@Composable
private fun WorkoutHeader(workout: WorkoutRecordEntity) {
    val type = remember(workout.exerciseType) { exerciseTypeToDisplayName(workout.exerciseType) }

    val (start, end, date) = remember(workout.startTime, workout.endTime) {
        val startInstant = Instant.ofEpochMilli(workout.startTime).atZone(ZoneId.systemDefault())
        val endInstant = Instant.ofEpochMilli(workout.endTime).atZone(ZoneId.systemDefault())
        Triple(
            startInstant.format(DateFormatUtils.WORKOUT_TIME_FORMATTER),
            endInstant.format(DateFormatUtils.WORKOUT_TIME_FORMATTER),
            startInstant.format(DateFormatUtils.WORKOUT_DATE_FORMATTER),
        )
    }

    Column {
        Text(text = type, style = MaterialTheme.typography.headlineMedium)
        Text(text = date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = "$start - $end (${workout.durationMinutes} min)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ZoneBreakdownCard(workout: WorkoutRecordEntity) {
    val totalMinutes = workout.durationMinutes.toFloat().coerceAtLeast(1f)
    val extendedColors = LocalExtendedColors.current
    val zones =
        listOf(
            Triple("Zone 5", workout.zone5Minutes, MaterialTheme.colorScheme.error),
            Triple("Zone 4", workout.zone4Minutes, extendedColors.warning),
            Triple("Zone 3", workout.zone3Minutes, extendedColors.success),
            Triple("Zone 2", workout.zone2Minutes, MaterialTheme.colorScheme.secondary),
            Triple("Zone 1", workout.zone1Minutes, MaterialTheme.colorScheme.onSurfaceVariant),
        )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Heart Rate Zones", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            zones.forEach { (label, minutes, color) ->
                ZoneRow(label, minutes, totalMinutes, color)
            }
        }
    }
}

@Composable
private fun ZoneRow(
    label: String,
    minutes: Float,
    totalMinutes: Float,
    color: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (minutes / totalMinutes).coerceIn(0f, 1f) },
            modifier =
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.15f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "%.0f min".format(minutes),
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun HrChartCard(
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

private fun computeLabelMinutes(durationMinutes: Int, target: Int): List<Double> {
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

@Composable
private fun HrChart(
    chartData: List<Pair<Double, Double>>,
    durationMinutes: Int,
) {
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

    val labelMinutes = remember(durationMinutes) {
        computeLabelMinutes(durationMinutes, TARGET_X_AXIS_LABELS)
    }

    val itemPlacer = remember(labelMinutes) {
        val base = HorizontalAxis.ItemPlacer.aligned(
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

    val labelComponent = ChartDefaults.labelTextComponent()
    val axisLabelComponent = ChartDefaults.axisLabelTextComponent()
    val guidelineComponent = ChartDefaults.guidelineComponent()

    CartesianChartHost(
        chart =
            rememberCartesianChart(
                rememberLineCartesianLayer(
                    lineProvider =
                        LineCartesianLayer.LineProvider.series(
                            LineCartesianLayer.rememberLine(
                                fill = LineCartesianLayer.LineFill.single(fill(MaterialTheme.colorScheme.primary)),
                            ),
                        ),
                ),
                startAxis =
                    VerticalAxis.rememberStart(
                        label = labelComponent,
                        title = "BPM",
                        titleComponent = axisLabelComponent,
                        guideline = guidelineComponent,
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        label = labelComponent,
                        title = "Minutes",
                        titleComponent = axisLabelComponent,
                        guideline = guidelineComponent,
                        valueFormatter = { _, value, _ -> value.roundToInt().toString() },
                        itemPlacer = itemPlacer,
                    ),
            ),
        modelProducer = modelProducer,
        zoomState = rememberVicoZoomState(
            zoomEnabled = false,
            initialZoom = Zoom.Content,
        ),
        modifier = Modifier.fillMaxWidth().height(200.dp),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HrrCard(uiState: WorkoutDetailUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Heart Rate Recovery",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MetricTooltip(
                    description = "A drop of 18+ bpm in the first minute is considered good.",
                    iconTint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            HrrItem("1 Minute", uiState.hrr1Min, 18)
            HrrItem("2 Minutes", uiState.hrr2Min, 35)
            HrrItem("3 Minutes", uiState.hrr3Min, null)
        }
    }
}

@Composable
private fun HrrItem(
    label: String,
    drop: Int?,
    threshold: Int?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (drop != null) {
            val color = if ((threshold != null) && (drop >= threshold)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Text("$drop bpm", style = MaterialTheme.typography.bodyLarge, color = color)
        } else {
            Text(
                "N/A",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
