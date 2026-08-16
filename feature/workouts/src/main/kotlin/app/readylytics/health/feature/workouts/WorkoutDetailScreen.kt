package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.readylytics.health.core.ui.R as CoreUiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailRoute(
    workoutId: String,
    onBack: () -> Unit,
    onRequestRoutePermission: (onGranted: () -> Unit) -> Unit = {},
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(CoreUiR.string.back),
                        )
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
                onGrantPermissionClick = {
                    onRequestRoutePermission {
                        viewModel.onRoutePermissionResult()
                    }
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
fun WorkoutDetailScreen(
    uiState: WorkoutDetailUiState,
    onGrantPermissionClick: () -> Unit = {},
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
        WorkoutDetailHeader(workout)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                TrainingLoadTile(
                    computedTrimp = uiState.computedTrimp,
                    workout = workout,
                    modifier = Modifier.weight(1f),
                )
                AvgPulseTile(
                    workout = workout,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                GainedStrainTile(
                    gainedStrain = uiState.gainedStrain,
                    gainedStrainDisplay = uiState.gainedStrainDisplay,
                    modifier = Modifier.weight(1f),
                )
                RasTile(
                    ras = uiState.ras,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
            ) {
                OverallLoadTile(
                    classification = uiState.classification,
                    modifier = Modifier.weight(1f),
                )
                IntensityTile(
                    classification = uiState.classification,
                    modifier = Modifier.weight(1f),
                )
            }

            val hasGpsMetrics =
                workout.totalDistanceMeters != null ||
                    workout.avgSpeedKmh != null ||
                    workout.elevationGainMeters != null ||
                    uiState.displayElevationGainMeters != null

            if (hasGpsMetrics) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    DistanceTile(
                        workout = workout,
                        unitSystem = uiState.unitSystem,
                        modifier = Modifier.weight(1f),
                    )
                    AvgPaceSpeedTile(
                        workout = workout,
                        unitSystem = uiState.unitSystem,
                        modifier = Modifier.weight(1f),
                    )
                }

                val elevationGain = uiState.displayElevationGainMeters ?: workout.elevationGainMeters
                if (elevationGain != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                    ) {
                        ElevationGainTile(
                            elevationGainMeters = elevationGain,
                            unitSystem = uiState.unitSystem,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        ZoneBreakdownCard(workout)

        RouteContourCard(
            uiState = uiState.routeUiState,
            onGrantPermissionClick = onGrantPermissionClick,
        )

        WorkoutPerformanceCharts(
            paceSpeedData = uiState.paceSpeedChartData,
            elevationData = uiState.elevationChartData,
            isPaceMode = uiState.isPaceMode,
            unitSystem = uiState.unitSystem,
            parentScrollInProgress = { scrollState.isScrollInProgress },
        )

        TrimpBreakdownChart(
            uiState.hrChartData,
            uiState.durationMinutes,
            parentScrollInProgress = { scrollState.isScrollInProgress },
        )

        WorkoutRecoverySection(uiState)
    }
}
