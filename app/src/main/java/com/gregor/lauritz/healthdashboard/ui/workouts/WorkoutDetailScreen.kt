package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    ) {
        item {
            WorkoutMetricsDisplay(workout, uiState.computedTrimp)
        }

        item {
            TrimpBreakdownChart(uiState.hrChartData, uiState.durationMinutes)
        }

        item {
            WorkoutEditForm(uiState)
        }
    }
}
