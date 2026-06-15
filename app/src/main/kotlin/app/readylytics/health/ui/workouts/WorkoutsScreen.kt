package app.readylytics.health.ui.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.ui.common.CardLoader
import app.readylytics.health.ui.common.ScreenHeaderSection
import app.readylytics.health.ui.common.SkeletonCard
import app.readylytics.health.ui.common.TimeRange
import app.readylytics.health.ui.components.ChartDefaults
import app.readylytics.health.ui.components.StatusLegend
import app.readylytics.health.ui.dashboard.DateSwitcher

@Composable
fun WorkoutsRoute(
    viewModel: WorkoutsViewModel = hiltViewModel(),
    onWorkoutClick: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()
    WorkoutsScreen(
        uiState = uiState,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onPreviousPage = viewModel::onPreviousPage,
        onNextPage = viewModel::onNextPage,
        onDateSelected = viewModel::onDateSelected,
        earliestDate = earliestDate,
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
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onWorkoutClick: (String) -> Unit,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    val (chartScrollState, chartZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedRange.days,
            key = uiState.selectedRange,
        )

    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeaderSection(isLoading = uiState.isLoading) { isDisabled ->
            DateSwitcher(
                selectedDate = uiState.selectedDate,
                onPreviousDay = onPreviousDay,
                onNextDay = onNextDay,
                onDateSelected = onDateSelected,
                earliestDate = earliestDate,
                enabled = !isDisabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(vertical = 16.dp),
        ) {
            WorkoutStatsSection(
                uiState = uiState,
                onRangeSelected = onRangeSelected,
                scrollState = chartScrollState,
                zoomState = chartZoomState,
                parentScrollInProgress = scrollState.isScrollInProgress,
            )

            CardLoader(
                isLoading = uiState.isLoading,
                skeleton = { WorkoutListSectionSkeleton() },
                content = {
                    WorkoutListSection(
                        workouts = uiState.recentWorkouts,
                        currentPage = uiState.currentPage,
                        totalPages = uiState.totalPages,
                        onPreviousPage = onPreviousPage,
                        onNextPage = onNextPage,
                        onWorkoutClick = onWorkoutClick,
                    )
                },
            )

            StatusLegend()
        }
    }
}

@Composable
private fun WorkoutListSectionSkeleton() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        repeat(3) {
            SkeletonCard(
                height = 80.dp,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}
