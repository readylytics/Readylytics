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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.component.LineComponent
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
            TrimpCard(workout.trimp)
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
private fun TrimpCard(trimp: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Training Load (TRIMP)", style = MaterialTheme.typography.titleSmall)
            Text(text = trimp.roundToInt().toString(), style = MaterialTheme.typography.headlineLarge)
        }
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

    val xSpacing = remember(durationMinutes) {
        maxOf(1, (durationMinutes / 5.0).roundToInt())
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

    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelComponent = rememberTextComponent(color = labelColor)
    val guidelineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)

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
                        titleComponent = rememberTextComponent(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        guideline = LineComponent(fill = fill(guidelineColor), thicknessDp = 1f),
                    ),
                bottomAxis =
                    HorizontalAxis.rememberBottom(
                        label = labelComponent,
                        title = "Minutes",
                        titleComponent = rememberTextComponent(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        guideline = LineComponent(fill = fill(guidelineColor), thicknessDp = 1f),
                        valueFormatter = { _, value, _ -> value.roundToInt().toString() },
                        itemPlacer = remember {
                            HorizontalAxis.ItemPlacer.aligned(spacing = { xSpacing }) // tick every 10 min
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
