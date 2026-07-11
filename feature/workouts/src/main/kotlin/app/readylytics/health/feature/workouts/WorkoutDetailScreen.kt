package app.readylytics.health.feature.workouts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.health.connect.client.PermissionController
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

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = PermissionController.createRequestPermissionResultContract(),
            onResult = { granted ->
                if (granted.contains("android.permission.health.READ_EXERCISE_ROUTES")) {
                    uiState.workout?.let { viewModel.loadRouteDetail(it) }
                }
            },
        )

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
                onRequestRoutePermission = {
                    permissionLauncher.launch(setOf("android.permission.health.READ_EXERCISE_ROUTES"))
                },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
fun WorkoutDetailScreen(
    uiState: WorkoutDetailUiState,
    onRequestRoutePermission: () -> Unit = {},
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
        } else if (uiState.routeUiState.state == RouteDataState.PermissionRequired) {
            RoutePermissionCard(onRequestPermission = onRequestRoutePermission)
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

@Composable
fun RoutePermissionCard(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.workout_route_permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            Text(
                text = stringResource(R.string.workout_route_permission_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.medium))
            Button(onClick = onRequestPermission) {
                Text(stringResource(R.string.workout_route_permission_button))
            }
        }
    }
}
