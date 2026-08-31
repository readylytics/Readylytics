package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.core.model.domain.workouts.WorkoutChartId
import app.readylytics.health.core.ui.common.TrendGranularity
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState

fun buildWorkoutsChartDataMap(
    uiState: WorkoutsUiState,
    rangeDays: Int,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    granularity: TrendGranularity,
    parentScrollInProgress: () -> Boolean,
    onFatigueRangeSelected: (app.readylytics.health.core.model.domain.workouts.FatigueCurveRange) -> Unit = {},
): Map<WorkoutChartId, @Composable (WorkoutChartConfiguration) -> Unit> =
    mapOf(
        WorkoutChartId.ACWR_TRIMP to { _: WorkoutChartConfiguration ->
            AcwrChartCard(
                trimpPoints = uiState.dailyTrimp,
                ratioPoints = uiState.dailyStrainRatio,
                rangeStartMs = uiState.rangeStartMs,
                rangeDays = rangeDays,
                scrollState = scrollState,
                zoomState = zoomState,
                parentScrollInProgress = parentScrollInProgress,
                granularity = granularity,
                modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            )
        },
        WorkoutChartId.WEEKLY_TRAINING to { _: WorkoutChartConfiguration ->
            WeeklyTrainingSection(
                stats = uiState.weeklyTraining,
                isLoading = uiState.isLoading,
                modifier = Modifier.fillMaxWidth(),
                parentScrollInProgress = parentScrollInProgress,
            )
        },
        WorkoutChartId.ACTIVITY_VOLUME to { _: WorkoutChartConfiguration ->
            ActivityVolumeSection(
                stats = uiState.weeklyTraining,
                isLoading = uiState.isLoading,
                unitSystem = uiState.unitSystem,
                hasDistancePermission = uiState.hasDistancePermission,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        WorkoutChartId.TRAINING_MIX to { _: WorkoutChartConfiguration ->
            TrainingMixSection(
                stats = uiState.weeklyTraining,
                isLoading = uiState.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        WorkoutChartId.RESIDUAL_FATIGUE_CURVE to { _: WorkoutChartConfiguration ->
            ResidualFatigueSection(
                uiState = uiState,
                onRangeSelected = onFatigueRangeSelected,
                parentScrollInProgress = parentScrollInProgress,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
