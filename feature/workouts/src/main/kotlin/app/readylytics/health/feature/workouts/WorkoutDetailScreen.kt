package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.workouts.R

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
                title = { Text(stringResource(R.string.label_workout_details)) },
                windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
    val scrollState = rememberScrollState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(MaterialTheme.spacing.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        WorkoutMetricsDisplay(
            workout = workout,
            computedTrimp = uiState.computedTrimp,
            gainedStrain = uiState.gainedStrain,
            gainedStrainDisplay = uiState.gainedStrainDisplay,
            ras = uiState.ras,
            classification = uiState.classification,
        )

        if (uiState.routeUiState.state == RouteDataState.Available) {
            RouteContourCard(routeUiState = uiState.routeUiState)
        }

        WorkoutPerformanceChartCard(
            title =
                stringResource(
                    if (uiState.isSpeedOriented) R.string.workout_chart_speed else R.string.workout_chart_pace,
                ),
            chartData = uiState.paceSpeedChartData,
            isInverted = !uiState.isSpeedOriented,
            yAxisTitle =
                stringResource(
                    if (uiState.isSpeedOriented) {
                        R.string.workout_chart_speed_unit
                    } else {
                        R.string.workout_chart_pace_unit
                    },
                ),
            xAxisTitle = stringResource(R.string.workout_chart_distance_unit),
        )

        WorkoutPerformanceChartCard(
            title = stringResource(R.string.workout_chart_elevation),
            chartData = uiState.elevationChartData,
            isInverted = false,
            yAxisTitle = stringResource(R.string.workout_chart_elevation_unit),
            xAxisTitle = stringResource(R.string.workout_chart_distance_unit),
        )

        TrimpBreakdownChart(
            uiState.hrChartData,
            uiState.durationMinutes,
            parentScrollInProgress = { scrollState.isScrollInProgress },
        )

        WorkoutRecoverySection(uiState)
    }
}
