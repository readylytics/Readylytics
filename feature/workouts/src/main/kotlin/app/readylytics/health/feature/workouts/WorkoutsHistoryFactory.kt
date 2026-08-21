package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutHistoryId

fun buildWorkoutsHistoryDataMap(
    uiState: WorkoutsUiState,
    currentPage: Int,
    totalPages: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onWorkoutClick: (String) -> Unit,
): Map<WorkoutHistoryId, @Composable (WorkoutHistoryConfiguration) -> Unit> =
    mapOf(
        WorkoutHistoryId.WORKOUT_LIST to { _: WorkoutHistoryConfiguration ->
            WorkoutListSection(
                workouts = uiState.recentWorkouts,
                currentPage = currentPage,
                totalPages = totalPages,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onWorkoutClick = onWorkoutClick,
            )
        },
    )
