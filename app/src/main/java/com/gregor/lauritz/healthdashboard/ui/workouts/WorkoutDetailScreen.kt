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
import com.gregor.lauritz.healthdashboard.ui.components.ChartDefaults
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.PaiWeeklyBar
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
                TrimpCard(
                    trimp = workout.trimp,
                    modifier = Modifier.weight(1f),
                )
                AvgPulseCard(
                    avgHr = workout.avgHr,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            ZoneBreakdownCard(workout)
        }

        item {
            HrChartCard(uiState.hrSamples, workout.startTime, workout.endTime)
        }

        item {
            HrrCard(uiState)
        }
    }
}

@Composable
private fun WorkoutHeader(workout: WorkoutRecordEntity) {
    val type = exerciseTypeToDisplayName(workout.exerciseType)
    val start = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(workout.startTime))
    val end = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(workout.endTime))
    val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(workout.startTime))

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
private fun TrimpCard(
    trimp: Float,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text("Training Load", style = MaterialTheme.typography.titleSmall)
            Text(text = trimp.roundToInt().toString(), style = MaterialTheme.typography.headlineMedium)
            Text("TRIMP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AvgPulseCard(
    avgHr: Int,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text("Avg Pulse", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (avgHr > 0) avgHr.toString() else "--",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text("BPM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ZoneBreakdownCard(workout: WorkoutRecordEntity) {
    val totalMinutes = workout.durationMinutes.toFloat().coerceAtLeast(1f)
    val zones =
        listOf(
            Triple("Zone 5", workout.zone5Minutes, MaterialTheme.colorScheme.error),
            Triple("Zone 4", workout.zone4Minutes, MaterialTheme.colorScheme.tertiary),
            Triple("Zone 3", workout.zone3Minutes, MaterialTheme.colorScheme.primary),
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
    samples: List<HeartRatePoint>,
    workoutStart: Long,
    workoutEnd: Long,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Heart Rate", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(16.dp))
            if (samples.isEmpty()) {
                Text("No HR data available")
            } else {
                HrChart(samples, workoutStart, workoutEnd)
            }
        }
    }
}

@Composable
private fun HrChart(
    samples: List<HeartRatePoint>,
    workoutStart: Long,
    workoutEnd: Long,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    val workoutStartInstant = Instant.ofEpochMilli(workoutStart)
    val workoutEndInstant = Instant.ofEpochMilli(workoutEnd)

    val workoutSamples = remember(samples, workoutStartInstant, workoutEndInstant) {
        samples.filter { it.timestamp in workoutStartInstant..workoutEndInstant }
    }

    val durationMinutes = remember(workoutStart, workoutEnd) {
        ChronoUnit.MINUTES.between(
            Instant.ofEpochMilli(workoutStart),
            Instant.ofEpochMilli(workoutEnd)
        ).toInt()
    }

    LaunchedEffect(workoutSamples) {
        if (workoutSamples.isEmpty()) return@LaunchedEffect
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = workoutSamples.map { (ChronoUnit.SECONDS.between(workoutStartInstant, it.timestamp).toDouble() / 60.0).let { m -> (m * 100.0).roundToInt() / 100.0 } },
                    y = workoutSamples.map { it.bpm.toDouble() },
                )
            }
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
                        itemPlacer = remember(durationMinutes) {
                            HorizontalAxis.ItemPlacer.aligned(spacing = {
                                maxOf(1, durationMinutes / 5)
                            })
                        },
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
private fun PaiProgressCard(totalPai: Float?) {
    val pai = totalPai ?: 0f
    val progress = (pai / 150f).coerceIn(0f, 1f)

    val status = when {
        pai >= 100f -> MetricStatus.OPTIMAL
        pai >= 75f -> MetricStatus.NEUTRAL
        pai >= 50f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

    val color = status.onContainerColor()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Weekly PAI Progress", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${pai.toInt()} / 100",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = color,
                trackColor = color.copy(alpha = 0.15f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    pai >= 100f -> "Optimal heart protection reached!"
                    pai >= 75f -> "Almost there! Keep it up."
                    pai >= 50f -> "Maintain consistency to reach 100."
                    else -> "Start moving to improve your heart health."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
