package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.preferences.UnitSystem

@Composable
fun WorkoutPerformanceCharts(
    paceSpeedData: List<Pair<Double, Double>>,
    elevationData: List<Pair<Double, Double>>,
    isPaceMode: Boolean,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    parentScrollInProgress: () -> Boolean = { false },
) {
    if (paceSpeedData.isEmpty() && elevationData.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        if (paceSpeedData.isNotEmpty()) {
            PaceSpeedChartCard(
                chartData = paceSpeedData,
                isPaceMode = isPaceMode,
                unitSystem = unitSystem,
                parentScrollInProgress = parentScrollInProgress,
            )
        }
        if (elevationData.isNotEmpty()) {
            ElevationChartCard(
                chartData = elevationData,
                unitSystem = unitSystem,
                parentScrollInProgress = parentScrollInProgress,
            )
        }
    }
}

@Composable
fun PaceSpeedChartCard(
    chartData: List<Pair<Double, Double>>,
    isPaceMode: Boolean,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    parentScrollInProgress: () -> Boolean = { false },
) {
    PerformanceChartCard(
        title =
            stringResource(
                if (isPaceMode) R.string.workout_chart_pace_title else R.string.workout_chart_speed_title,
            ),
        modifier = modifier,
    ) {
        DistanceSeriesChart(
            chartData = chartData,
            kind = if (isPaceMode) DistanceSeriesKind.PACE else DistanceSeriesKind.SPEED,
            unitSystem = unitSystem,
            parentScrollInProgress = parentScrollInProgress,
        )
    }
}

@Composable
fun ElevationChartCard(
    chartData: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    parentScrollInProgress: () -> Boolean = { false },
) {
    PerformanceChartCard(
        title = stringResource(R.string.workout_chart_elevation_title),
        modifier = modifier,
    ) {
        DistanceSeriesChart(
            chartData = chartData,
            kind = DistanceSeriesKind.ELEVATION,
            unitSystem = unitSystem,
            parentScrollInProgress = parentScrollInProgress,
        )
    }
}

@Composable
private fun PerformanceChartCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(Modifier.padding(MaterialTheme.spacing.medium)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            content()
        }
    }
}
