package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.MetricCardSkeleton
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.StepsCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.insights.InsightParams
import app.readylytics.health.domain.insights.detail.DailyInsightContext
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.scoring.toStatus
import app.readylytics.health.domain.scoring.toTimeString
import kotlin.math.roundToInt

// Renders a single catalog-registered metric card: resolves requested/render mode through
// DashboardCardCatalog and dispatches to the shared DashboardMetricCard shell (Gauge/Bar/Value).
// Used by every configurable card in buildCardDataMap below; Steps and Insights are fixed/bespoke
// and do not go through this helper.
@Composable
private fun ConfigurableMetricCard(
    cardId: CardId,
    presentation: UniversalMetricPresentation?,
    configuration: CardConfiguration,
    isEditing: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
    skeleton: @Composable () -> Unit = { MetricCardSkeleton() },
) {
    CardLoader(
        isLoading = isLoading,
        skeleton = skeleton,
        content = {
            val spec = DashboardCardCatalog.spec(cardId)
            if (presentation != null && spec != null) {
                DashboardMetricCard(
                    presentation = presentation,
                    specification = spec,
                    requestedMode = DashboardCardCatalog.requestedMode(configuration),
                    isEditing = isEditing,
                    onModeSelected = { mode -> onCardDisplayModeChanged(cardId, mode) },
                    onClick = if (isEditing) null else onClick,
                )
            }
        },
    )
}

// Build a map of CardId to composable card content for the Dashboard screen
// This factory method creates all available dashboard cards and maps them by ID
// for use with the ReorderableCardGrid component
fun buildCardDataMap(
    uiState: DashboardUiState,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onNavigateToSteps: () -> Unit = {},
    onNavigateToHeartRate: () -> Unit = {},
    onNavigateToHrv: () -> Unit = {},
    onNavigateToWeight: () -> Unit = {},
    onNavigateToBodyFat: () -> Unit = {},
    onNavigateToBloodPressure: () -> Unit = {},
    onNavigateToVitals: () -> Unit = {},
    isEditing: Boolean = false,
    isLoading: Boolean = false,
    onDismissInsight: (InsightType) -> Unit = {},
    onRestoreInsights: () -> Unit = {},
    onOpenInsight: (InsightParams) -> Unit = {},
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> },
    insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit,
): Map<CardId, @Composable (CardConfiguration) -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable (CardConfiguration) -> Unit>()

    cardMap[CardId.SLEEP_SCORE] = { configuration ->
        val sleepScoreCard = uiState.cardDataMap[CardId.SLEEP_SCORE]
        val deltaText =
            formatRoundedScoreDelta(
                currentRounded = (sleepScoreCard?.visual as? UniversalMetricVisual.Score)?.rawValue?.roundToInt(),
                previousRounded = uiState.yesterdaySleepScoreRounded,
            ).resolveOrNull()
        ConfigurableMetricCard(
            cardId = CardId.SLEEP_SCORE,
            presentation = deltaText?.let { sleepScoreCard?.copy(secondaryText = it) } ?: sleepScoreCard,
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            skeleton = { ScoreDialSkeleton() },
            onClick = onNavigateToSleep,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.READINESS] = { configuration ->
        val readinessCard = uiState.cardDataMap[CardId.READINESS]
        val readinessVal = (readinessCard?.visual as? UniversalMetricVisual.Score)?.rawValue
        val readinessDelta =
            formatRoundedScoreDelta(
                currentRounded = readinessVal?.roundToInt(),
                previousRounded = uiState.yesterdayReadiness?.toInt(),
            ).resolveOrNull()
        ConfigurableMetricCard(
            cardId = CardId.READINESS,
            presentation = readinessDelta?.let { readinessCard?.copy(secondaryText = it) } ?: readinessCard,
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            skeleton = { ScoreDialSkeleton() },
            onClick = onNavigateToWorkouts,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    if (uiState.activeInsightTypes.isNotEmpty() || isEditing) {
        cardMap[CardId.INSIGHTS] = {
            insightsCard(uiState, isEditing, onDismissInsight, onRestoreInsights, onOpenInsight)
        }
    }

    cardMap[CardId.STEPS] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                StepsCard(
                    stepCount = uiState.stepCount,
                    stepGoal = uiState.stepGoal,
                    onClick = if (isEditing) ({}) else onNavigateToSteps,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    cardMap[CardId.HRV] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.HRV,
            presentation = uiState.cardDataMap[CardId.HRV],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToHrv,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.SLEEP_RHR] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.SLEEP_RHR,
            presentation = uiState.cardDataMap[CardId.SLEEP_RHR],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToSleep,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.STRAIN_RATIO] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.STRAIN_RATIO,
            presentation = uiState.cardDataMap[CardId.STRAIN_RATIO],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToWorkouts,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.SLEEP_DURATION] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.SLEEP_DURATION,
            presentation = uiState.cardDataMap[CardId.SLEEP_DURATION],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToSleep,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.SLEEP_EFFICIENCY] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.SLEEP_EFFICIENCY,
            presentation = uiState.cardDataMap[CardId.SLEEP_EFFICIENCY],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToSleep,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.RAS_DAILY] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.RAS_DAILY,
            presentation = uiState.cardDataMap[CardId.RAS_DAILY],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToWorkouts,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.RESTING_HR] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.RESTING_HR,
            presentation = uiState.cardDataMap[CardId.RESTING_HR],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToRhr,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.CIRCADIAN_CONSISTENCY] = { configuration ->
        val circadianCard = uiState.cardDataMap[CardId.CIRCADIAN_CONSISTENCY]
        val result = uiState.circadianConsistency
        val thresholdMinutes =
            when (result) {
                is CircadianConsistencyResult.Ready -> result.thresholdMinutes
                else -> SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES
            }
        val windowText =
            (result as? CircadianConsistencyResult.Ready)?.let {
                stringResource(
                    app.readylytics.health.core.ui.R.string.label_circadian_median,
                    it.medianBedtimeMinutes.toTimeString(),
                    it.medianWakeMinutes.toTimeString(),
                )
            }
        val tooltipText =
            stringResource(app.readylytics.health.core.ui.R.string.tooltip_circadian_score, thresholdMinutes)
        ConfigurableMetricCard(
            cardId = CardId.CIRCADIAN_CONSISTENCY,
            presentation =
                circadianCard?.copy(
                    tooltip = tooltipText,
                    secondaryText = windowText ?: circadianCard.secondaryText,
                    status = result?.toStatus() ?: circadianCard.status,
                ),
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToSleep,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.HEART_RATE] = { configuration ->
        val tooltipText = stringResource(R.string.tooltip_heart_rate_card)
        ConfigurableMetricCard(
            cardId = CardId.HEART_RATE,
            presentation = uiState.cardDataMap[CardId.HEART_RATE]?.copy(tooltip = tooltipText),
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToHeartRate,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.WEIGHT] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.WEIGHT,
            presentation = uiState.cardDataMap[CardId.WEIGHT],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToWeight,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.BODY_FAT] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.BODY_FAT,
            presentation = uiState.cardDataMap[CardId.BODY_FAT],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToBodyFat,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.BLOOD_PRESSURE] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.BLOOD_PRESSURE,
            presentation = uiState.cardDataMap[CardId.BLOOD_PRESSURE],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToBloodPressure,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    cardMap[CardId.OXYGEN_SATURATION] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.OXYGEN_SATURATION,
            presentation = uiState.cardDataMap[CardId.OXYGEN_SATURATION],
            configuration = configuration,
            isEditing = isEditing,
            isLoading = isLoading,
            onClick = onNavigateToVitals,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
        )
    }

    return cardMap
}

fun DashboardUiState.toDailyInsightContext(): DailyInsightContext =
    DailyInsightContext(
        date = selectedDate,
        sleepScore = summary?.sleepScore,
        sleepDurationMinutes = summary?.sleepDurationMinutes,
        goalSleepMinutes = (goalSleepHours * 60).toInt(),
        zLnHrv = summary?.zLnHrv,
        zRhr = summary?.zRhr,
        rhrDeltaBpm = summary?.readinessResult?.diagnostics?.rhrDeltaBpm,
        readinessScore = summary?.let { LoadSourceSelector.selectReadiness(it, userPreferences.strainLoadSourceMode) },
        yesterdayTrimp = null,
        strainRatio = summary?.let { LoadSourceSelector.selectStrainRatio(it, userPreferences.strainLoadSourceMode) },
        acute7dLoad = null,
        chronic28dLoad = null,
        stepCount = summary?.stepCount,
        stepGoal = stepGoal,
        bloodPressureSystolic = summary?.bloodPressureSystolic,
        bloodPressureBaselineSystolic = null,
        avgSleepingSpo2 = summary?.avgSleepingSpo2,
        weightKg = summary?.weightKg,
        previousWeightKg = null,
        bedtimeOffsetMinutes = null,
        lastWorkoutEndedMinutesBeforeSleep = null,
        workoutDurationMinutes = null,
        workoutIntensityCategory = null,
    )

fun getInsightIcon(type: InsightType): ImageVector =
    when (type) {
        InsightType.LATE_NADIR -> Icons.Default.Schedule
        InsightType.SICK_INDICATOR -> Icons.Default.MonitorHeart
        InsightType.STRONG_RECOVERY_SIGNAL -> Icons.AutoMirrored.Filled.TrendingUp
        InsightType.LOAD_SPIKE_RECOVERY_STRAIN -> Icons.Default.MonitorHeart
        InsightType.WORKOUT_IMPACT -> Icons.Default.MonitorHeart
        InsightType.REST_DAY_SUCCESS -> Icons.AutoMirrored.Filled.TrendingUp
        InsightType.REST_DAY_NO_IMPACT -> Icons.Default.Schedule
        InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS -> Icons.Default.Bedtime
        InsightType.HIGH_STRAIN_SLEEP_DEFICIT -> Icons.Default.MonitorHeart
        InsightType.LATE_NADIR_SHORT_SLEEP -> Icons.Default.Schedule
        InsightType.RECOVERY_HRV_MISSING -> Icons.Default.Info
        InsightType.RECOVERY_SUSPICIOUS_STAGE_RATIO -> Icons.Default.Info
        InsightType.HRV_DROP_LOW_SPO2 -> Icons.Default.Air
        InsightType.LATE_NADIR_ELEVATED_RHR -> Icons.Default.MonitorHeart
        InsightType.BP_ELEVATED_HIGH_STRAIN -> Icons.Default.Bloodtype
        InsightType.RAS_DEPLETION_HIGH_STRAIN -> Icons.Default.FitnessCenter
        InsightType.HRV_DECLINE_STREAK -> Icons.Default.Warning
        InsightType.STEP_SHORTFALL -> Icons.AutoMirrored.Filled.DirectionsWalk
        InsightType.RAS_WEEKLY_UNDERPERFORMANCE -> Icons.AutoMirrored.Filled.TrendingUp
        InsightType.WEIGHT_DRIFT_TRAINING_LOAD -> Icons.Default.MonitorWeight
    }
