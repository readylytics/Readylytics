package com.gregor.lauritz.healthdashboard.ui.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.ui.common.ScoreDialSkeleton
import com.gregor.lauritz.healthdashboard.ui.common.SkeletonCard
import com.gregor.lauritz.healthdashboard.ui.common.TimeRange
import com.gregor.lauritz.healthdashboard.ui.components.StatusLegend
import com.gregor.lauritz.healthdashboard.ui.dashboard.DateSwitcher
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState

@Composable
fun WorkoutsRoute(
    viewModel: WorkoutsViewModel = hiltViewModel(),
    onWorkoutClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    WorkoutsScreen(
        uiState = uiState,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onWorkoutClick = onWorkoutClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsScreen(
    uiState: WorkoutsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onWorkoutClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    rememberSaveable(saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
        listState
    }

    val rangeDays = uiState.selectedRange.days
    val (chartScrollState, chartZoomState) =
        key(uiState.selectedRange) {
            val scrollState = rememberVicoScrollState(scrollEnabled = rangeDays > 7)
            val zoomState =
                rememberVicoZoomState(
                    zoomEnabled = rangeDays > 7,
                    initialZoom = Zoom.Content,
                    minZoom = Zoom.Content,
                    maxZoom =
                        remember(rangeDays) {
                            when (rangeDays) {
                                30 -> Zoom.fixed(6f)
                                180 -> Zoom.fixed(25f)
                                else -> Zoom.Content
                            }
                        },
                )
            scrollState to zoomState
        }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item(key = "date_switcher") {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            ) {
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item(key = "stats_section") {
            if (uiState.isLoading) {
                WorkoutStatsSectionSkeleton()
            } else {
                WorkoutStatsSection(
                    uiState = uiState,
                    onRangeSelected = onRangeSelected,
                    scrollState = chartScrollState,
                    zoomState = chartZoomState,
                )
            }
        }

        item(key = "list_section") {
            if (uiState.isLoading) {
                WorkoutListSectionSkeleton()
            } else {
                WorkoutListSection(
                    workouts = uiState.recentWorkouts,
                    onWorkoutClick = onWorkoutClick,
                )
            }
        }

        item(key = "status_legend") {
            StatusLegend()
        }
    }
}

@Composable
private fun WorkoutStatsSectionSkeleton() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScoreDialSkeleton(height = 130.dp)
            ScoreDialSkeleton(height = 130.dp)
        }
        SkeletonCard(height = 120.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        SkeletonCard(height = 180.dp, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WorkoutListSectionSkeleton() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        repeat(3) {
            SkeletonCard(
                height = 80.dp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}
