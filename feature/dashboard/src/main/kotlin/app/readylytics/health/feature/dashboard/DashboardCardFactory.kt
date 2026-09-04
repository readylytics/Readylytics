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
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.model.domain.model.LoadSourceSelector
import app.readylytics.health.core.scoring.domain.insights.InsightParams
import app.readylytics.health.core.scoring.domain.insights.detail.DailyInsightContext
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.core.scoring.domain.scoring.toStatus
import app.readylytics.health.core.scoring.domain.scoring.toTimeString
import app.readylytics.health.core.ui.common.CardLoader
import app.readylytics.health.core.ui.common.MetricCardSkeleton
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.StepsCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.core.ui.components.metriccard.toDashboardMode
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode
import kotlin.math.roundToInt
import app.readylytics.health.core.ui.R as UiR

private data class DashboardCardContext(
    val uiState: DashboardUiState,
    val isEditing: Boolean,
    val isLoading: Boolean,
    val onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
)

// Renders a single catalog-registered metric card: resolves requested/render mode through
// DashboardCardCatalog and dispatches to the shared DashboardMetricCard shell (Gauge/Bar/Value).
// Used by every configurable card in buildCardDataMap below; Steps and Insights are fixed/bespoke
// and do not go through this helper.
@Composable
private fun ConfigurableMetricCard(
    cardId: CardId,
    presentation: UniversalMetricPresentation?,
    configuration: CardConfiguration,
    ctx: DashboardCardContext,
    onClick: () -> Unit,
    skeleton: @Composable () -> Unit = { MetricCardSkeleton() },
) {
    CardLoader(
        isLoading = ctx.isLoading,
        skeleton = skeleton,
        content = {
            val spec = DashboardCardCatalog.spec(cardId)
            if (presentation != null && spec != null) {
                UniversalMetricCard(
                    presentation = presentation,
                    specification = spec.toUniversalSpec(cardId.usesDeltaPill()),
                    requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                    isEditing = ctx.isEditing,
                    onModeSelected = { mode -> ctx.onCardDisplayModeChanged(cardId, mode.toDashboardMode()) },
                    onClick = if (ctx.isEditing) null else onClick,
                )
            }
        },
    )
}

private data class SpecialCardCallbacks(
    val onDismissInsight: (InsightType) -> Unit,
    val onRestoreInsights: () -> Unit,
    val onOpenInsight: (InsightParams) -> Unit,
    val onCopySetupPrompt: () -> Unit,
    val onCopyDailyPrompt: () -> Unit,
    val insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit,
)

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
    onNavigateToCardioFitness: () -> Unit = {},
    isEditing: Boolean = false,
    isLoading: Boolean = false,
    onDismissInsight: (InsightType) -> Unit = {},
    onRestoreInsights: () -> Unit = {},
    onOpenInsight: (InsightParams) -> Unit = {},
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> },
    onCopySetupPrompt: () -> Unit = {},
    onCopyDailyPrompt: () -> Unit = {},
    insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit,
): Map<CardId, @Composable (CardConfiguration) -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable (CardConfiguration) -> Unit>()
    val ctx = DashboardCardContext(uiState, isEditing, isLoading, onCardDisplayModeChanged)

    registerSleepCards(cardMap, ctx, onNavigateToSleep)
    registerWorkoutCards(cardMap, ctx, onNavigateToWorkouts)
    registerVitalsCards(
        cardMap = cardMap,
        ctx = ctx,
        onNavigateToHrv = onNavigateToHrv,
        onNavigateToRhr = onNavigateToRhr,
        onNavigateToVitals = onNavigateToVitals,
        onNavigateToCardioFitness = onNavigateToCardioFitness,
    )
    registerBodyMetricCards(
        cardMap = cardMap,
        ctx = ctx,
        onNavigateToSteps = onNavigateToSteps,
        onNavigateToHeartRate = onNavigateToHeartRate,
        onNavigateToWeight = onNavigateToWeight,
        onNavigateToBodyFat = onNavigateToBodyFat,
        onNavigateToBloodPressure = onNavigateToBloodPressure,
    )
    registerSpecialCards(
        cardMap = cardMap,
        ctx = ctx,
        callbacks =
            SpecialCardCallbacks(
                onDismissInsight = onDismissInsight,
                onRestoreInsights = onRestoreInsights,
                onOpenInsight = onOpenInsight,
                onCopySetupPrompt = onCopySetupPrompt,
                onCopyDailyPrompt = onCopyDailyPrompt,
                insightsCard = insightsCard,
            ),
    )

    return cardMap
}

private fun registerSleepCards(
    cardMap: MutableMap<CardId, @Composable (CardConfiguration) -> Unit>,
    ctx: DashboardCardContext,
    onNavigateToSleep: () -> Unit,
) {
    cardMap[CardId.SLEEP_SCORE] = { configuration ->
        val sleepScoreCard = ctx.uiState.cardDataMap[CardId.SLEEP_SCORE]
        val deltaText =
            formatRoundedScoreDelta(
                currentRounded = (sleepScoreCard?.visual as? UniversalMetricVisual.Score)?.rawValue?.roundToInt(),
                previousRounded = ctx.uiState.yesterdaySleepScoreRounded,
            ).resolveOrNull()
        ConfigurableMetricCard(
            cardId = CardId.SLEEP_SCORE,
            presentation = deltaText?.let { sleepScoreCard?.copy(secondaryText = it) } ?: sleepScoreCard,
            configuration = configuration,
            ctx = ctx,
            skeleton = { ScoreDialSkeleton() },
            onClick = onNavigateToSleep,
        )
    }

    listOf(CardId.SLEEP_RHR, CardId.SLEEP_DURATION, CardId.SLEEP_EFFICIENCY).forEach { id ->
        cardMap[id] = { configuration ->
            ConfigurableMetricCard(
                cardId = id,
                presentation = ctx.uiState.cardDataMap[id],
                configuration = configuration,
                ctx = ctx,
                onClick = onNavigateToSleep,
            )
        }
    }

    cardMap[CardId.CIRCADIAN_CONSISTENCY] = { configuration ->
        val circadianCard = ctx.uiState.cardDataMap[CardId.CIRCADIAN_CONSISTENCY]
        val result = ctx.uiState.circadianConsistency
        val ready = result as? CircadianConsistencyResult.Ready
        val thresholdMinutes = ready?.thresholdMinutes ?: SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES
        val windowText =
            ready?.let {
                stringResource(
                    UiR.string.label_circadian_median,
                    it.medianBedtimeMinutes.toTimeString(),
                    it.medianWakeMinutes.toTimeString(),
                )
            }
        val tooltipText = stringResource(UiR.string.tooltip_circadian_score, thresholdMinutes)
        ConfigurableMetricCard(
            cardId = CardId.CIRCADIAN_CONSISTENCY,
            presentation =
                circadianCard?.copy(
                    tooltip = tooltipText,
                    secondaryText = windowText ?: circadianCard.secondaryText,
                    status = result?.toStatus() ?: circadianCard.status,
                ),
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToSleep,
        )
    }
}

private fun registerWorkoutCards(
    cardMap: MutableMap<CardId, @Composable (CardConfiguration) -> Unit>,
    ctx: DashboardCardContext,
    onNavigateToWorkouts: () -> Unit,
) {
    cardMap[CardId.READINESS] = { configuration ->
        val readinessCard = ctx.uiState.cardDataMap[CardId.READINESS]
        val readinessVal = (readinessCard?.visual as? UniversalMetricVisual.Score)?.rawValue
        val readinessDelta =
            formatRoundedScoreDelta(
                currentRounded = readinessVal?.roundToInt(),
                previousRounded = ctx.uiState.yesterdayReadiness?.toInt(),
            ).resolveOrNull()
        ConfigurableMetricCard(
            cardId = CardId.READINESS,
            presentation = readinessDelta?.let { readinessCard?.copy(secondaryText = it) } ?: readinessCard,
            configuration = configuration,
            ctx = ctx,
            skeleton = { ScoreDialSkeleton() },
            onClick = onNavigateToWorkouts,
        )
    }

    listOf(
        CardId.STRAIN_RATIO,
        CardId.RAS_DAILY,
        CardId.RESIDUAL_FATIGUE,
        CardId.TRAINING_READINESS,
        CardId.TSB,
    ).forEach { id ->
        cardMap[id] = { configuration ->
            ConfigurableMetricCard(
                cardId = id,
                presentation = ctx.uiState.cardDataMap[id],
                configuration = configuration,
                ctx = ctx,
                onClick = onNavigateToWorkouts,
            )
        }
    }
}

private fun registerVitalsCards(
    cardMap: MutableMap<CardId, @Composable (CardConfiguration) -> Unit>,
    ctx: DashboardCardContext,
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onNavigateToVitals: () -> Unit,
    onNavigateToCardioFitness: () -> Unit,
) {
    cardMap[CardId.HRV] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.HRV,
            presentation = ctx.uiState.cardDataMap[CardId.HRV],
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToHrv,
        )
    }

    cardMap[CardId.RESTING_HR] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.RESTING_HR,
            presentation = ctx.uiState.cardDataMap[CardId.RESTING_HR],
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToRhr,
        )
    }

    cardMap[CardId.OXYGEN_SATURATION] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.OXYGEN_SATURATION,
            presentation = ctx.uiState.cardDataMap[CardId.OXYGEN_SATURATION],
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToVitals,
        )
    }

    cardMap[CardId.BODY_TEMPERATURE] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.BODY_TEMPERATURE,
            presentation = ctx.uiState.cardDataMap[CardId.BODY_TEMPERATURE],
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToVitals,
        )
    }

    cardMap[CardId.CARDIO_FITNESS] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.CARDIO_FITNESS,
            presentation = ctx.uiState.cardDataMap[CardId.CARDIO_FITNESS],
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToCardioFitness,
        )
    }
}

private fun registerBodyMetricCards(
    cardMap: MutableMap<CardId, @Composable (CardConfiguration) -> Unit>,
    ctx: DashboardCardContext,
    onNavigateToSteps: () -> Unit,
    onNavigateToHeartRate: () -> Unit,
    onNavigateToWeight: () -> Unit,
    onNavigateToBodyFat: () -> Unit,
    onNavigateToBloodPressure: () -> Unit,
) {
    cardMap[CardId.STEPS] = {
        CardLoader(
            isLoading = ctx.isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                StepsCard(
                    stepCount = ctx.uiState.stepCount,
                    stepGoal = ctx.uiState.stepGoal,
                    onClick = if (ctx.isEditing) ({}) else onNavigateToSteps,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }

    cardMap[CardId.HEART_RATE] = { configuration ->
        val tooltipText = stringResource(R.string.tooltip_heart_rate_card)
        ConfigurableMetricCard(
            cardId = CardId.HEART_RATE,
            presentation = ctx.uiState.cardDataMap[CardId.HEART_RATE]?.copy(tooltip = tooltipText),
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToHeartRate,
        )
    }

    cardMap[CardId.WEIGHT] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.WEIGHT,
            presentation = ctx.uiState.cardDataMap[CardId.WEIGHT],
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToWeight,
        )
    }

    cardMap[CardId.BODY_FAT] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.BODY_FAT,
            presentation = ctx.uiState.cardDataMap[CardId.BODY_FAT],
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToBodyFat,
        )
    }

    cardMap[CardId.BLOOD_PRESSURE] = { configuration ->
        ConfigurableMetricCard(
            cardId = CardId.BLOOD_PRESSURE,
            presentation = ctx.uiState.cardDataMap[CardId.BLOOD_PRESSURE],
            configuration = configuration,
            ctx = ctx,
            onClick = onNavigateToBloodPressure,
        )
    }
}

private fun registerSpecialCards(
    cardMap: MutableMap<CardId, @Composable (CardConfiguration) -> Unit>,
    ctx: DashboardCardContext,
    callbacks: SpecialCardCallbacks,
) {
    if (ctx.uiState.activeInsightTypes.isNotEmpty() || ctx.isEditing) {
        cardMap[CardId.INSIGHTS] = {
            callbacks.insightsCard(
                ctx.uiState,
                ctx.isEditing,
                callbacks.onDismissInsight,
                callbacks.onRestoreInsights,
                callbacks.onOpenInsight,
            )
        }
    }

    cardMap[CardId.AI_RECOMMENDATION] = {
        AiRecommendationCard(
            onCopySetupPrompt = callbacks.onCopySetupPrompt,
            onCopyDailyPrompt = callbacks.onCopyDailyPrompt,
        )
    }
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
