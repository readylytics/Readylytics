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
import app.readylytics.health.core.ui.components.CircadianConsistencyCard
import app.readylytics.health.core.ui.components.M3ScoreGaugeCard
import app.readylytics.health.core.ui.components.MetricCard
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.insights.InsightParams
import app.readylytics.health.domain.insights.detail.DailyInsightContext
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.scoring.toStatus
import app.readylytics.health.domain.scoring.toTimeString
import app.readylytics.health.domain.util.roundToPercentInt

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
    insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit,
): Map<CardId, @Composable () -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable () -> Unit>()

    val summary = uiState.summary

    cardMap[CardId.SLEEP_SCORE] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { ScoreDialSkeleton() },
            content = {
                val sleepScoreCard = uiState.cardDataMap[CardId.SLEEP_SCORE]
                M3ScoreGaugeCard(
                    title = sleepScoreCard?.title ?: "Sleep Score",
                    score = summary?.sleepScore,
                    displayText = sleepScoreCard?.value ?: "—",
                    unitText = sleepScoreCard?.unit ?: "",
                    status = sleepScoreCard?.status,
                    deltaText =
                        formatRoundedScoreDelta(
                            currentRounded = sleepScoreCard?.value?.toIntOrNull(),
                            previousRounded = uiState.yesterdaySleepScoreRounded,
                        ).resolveOrNull(),
                    onClick = if (isEditing) ({}) else onNavigateToSleep,
                    tooltipDescription = sleepScoreCard?.tooltip,
                )
            },
        )
    }

    cardMap[CardId.READINESS] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { ScoreDialSkeleton() },
            content = {
                val readinessCard = uiState.cardDataMap[CardId.READINESS]
                val readinessVal = readinessCard?.value?.toFloatOrNull()
                val readinessDelta =
                    formatRoundedScoreDelta(
                        currentRounded = readinessVal?.toInt(),
                        previousRounded = uiState.yesterdayReadiness?.toInt(),
                    ).resolveOrNull()
                M3ScoreGaugeCard(
                    title = readinessCard?.title ?: "Readiness",
                    score = readinessVal,
                    displayText = readinessCard?.value ?: "—",
                    unitText = readinessCard?.unit ?: "",
                    status = readinessCard?.status,
                    deltaText = readinessDelta,
                    onClick = if (isEditing) ({}) else onNavigateToWorkouts,
                    tooltipDescription = readinessCard?.tooltip,
                )
            },
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

    cardMap[CardId.HRV] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val hrvCard = uiState.cardDataMap[CardId.HRV]
                if (hrvCard != null) {
                    MetricCard(
                        title = hrvCard.title,
                        value = hrvCard.value,
                        secondaryText = hrvCard.unit,
                        status = hrvCard.status,
                        onClick = if (isEditing) null else onNavigateToHrv,
                        tooltip = hrvCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.SLEEP_RHR] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val sleepRhrCard = uiState.cardDataMap[CardId.SLEEP_RHR]
                if (sleepRhrCard != null) {
                    MetricCard(
                        title = sleepRhrCard.title,
                        value = sleepRhrCard.value,
                        secondaryText = sleepRhrCard.unit,
                        status = sleepRhrCard.status,
                        onClick = if (isEditing) null else onNavigateToSleep,
                        tooltip = sleepRhrCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.STRAIN_RATIO] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val strainCard = uiState.cardDataMap[CardId.STRAIN_RATIO]
                if (strainCard != null) {
                    MetricCard(
                        title = strainCard.title,
                        value = strainCard.value,
                        secondaryText = strainCard.unit,
                        status = strainCard.status,
                        onClick = if (isEditing) null else onNavigateToWorkouts,
                        tooltip = strainCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.SLEEP_DURATION] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val durationCard = uiState.cardDataMap[CardId.SLEEP_DURATION]
                if (durationCard != null) {
                    MetricCard(
                        title = durationCard.title,
                        value = durationCard.value,
                        secondaryText = durationCard.secondaryText ?: durationCard.unit,
                        status = durationCard.status,
                        onClick = if (isEditing) null else onNavigateToSleep,
                        tooltip = durationCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.SLEEP_EFFICIENCY] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val efficiencyCard = uiState.cardDataMap[CardId.SLEEP_EFFICIENCY]
                if (efficiencyCard != null) {
                    MetricCard(
                        title = efficiencyCard.title,
                        value = efficiencyCard.value,
                        secondaryText = efficiencyCard.secondaryText ?: efficiencyCard.unit,
                        status = efficiencyCard.status,
                        onClick = if (isEditing) null else onNavigateToSleep,
                        tooltip = efficiencyCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.RAS_DAILY] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val rasCard = uiState.cardDataMap[CardId.RAS_DAILY]
                if (rasCard != null) {
                    MetricCard(
                        title = rasCard.title,
                        value = rasCard.value,
                        secondaryText = rasCard.unit,
                        status = rasCard.status,
                        onClick = if (isEditing) null else onNavigateToWorkouts,
                        tooltip = rasCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.RESTING_HR] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                if (uiState.restingHrCard != null) {
                    val card = uiState.restingHrCard
                    MetricCard(
                        title = card.title,
                        value = card.value,
                        secondaryText = card.unit,
                        status = card.status,
                        onClick = if (isEditing) null else onNavigateToRhr,
                        tooltip = card.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.CIRCADIAN_CONSISTENCY] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                if (uiState.circadianConsistency != null) {
                    val result = uiState.circadianConsistency
                    val scoreText =
                        when (result) {
                            is CircadianConsistencyResult.Calibrating ->
                                stringResource(
                                    app.readylytics.health.core.ui.R.string.spo2_calibrating,
                                )
                            is CircadianConsistencyResult.MissingData -> "—"
                            is CircadianConsistencyResult.Ready -> "${result.score.roundToPercentInt()}%"
                        }
                    val windowText =
                        when (result) {
                            is CircadianConsistencyResult.Calibrating,
                            is CircadianConsistencyResult.MissingData,
                            -> null
                            is CircadianConsistencyResult.Ready ->
                                stringResource(
                                    app.readylytics.health.core.ui.R.string.label_circadian_median,
                                    result.medianBedtimeMinutes.toTimeString(),
                                    result.medianWakeMinutes.toTimeString(),
                                )
                        }
                    val thresholdMinutes =
                        when (result) {
                            is CircadianConsistencyResult.Calibrating,
                            is CircadianConsistencyResult.MissingData,
                            -> SettingsDefaults.CONSISTENCY_THRESHOLD_MINUTES
                            is CircadianConsistencyResult.Ready -> result.thresholdMinutes
                        }
                    val tooltipText =
                        stringResource(
                            app.readylytics.health.core.ui.R.string.tooltip_circadian_score,
                            thresholdMinutes,
                        )

                    CircadianConsistencyCard(
                        scoreText = scoreText,
                        windowText = windowText,
                        status = result.toStatus(),
                        tooltipText = tooltipText,
                        onClick = if (isEditing) ({}) else onNavigateToSleep,
                    )
                }
            },
        )
    }

    cardMap[CardId.HEART_RATE] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                HeartRateCard(
                    summary = uiState.heartRateDaySummary,
                    onClick = if (isEditing) ({}) else onNavigateToHeartRate,
                )
            },
        )
    }

    cardMap[CardId.WEIGHT] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val weightCard = uiState.cardDataMap[CardId.WEIGHT]
                if (weightCard != null) {
                    MetricCard(
                        title = weightCard.title,
                        value = weightCard.value,
                        secondaryText = weightCard.secondaryText ?: weightCard.unit,
                        status = weightCard.status,
                        onClick = if (isEditing) null else onNavigateToWeight,
                        tooltip = weightCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.BODY_FAT] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val bodyFatCard = uiState.cardDataMap[CardId.BODY_FAT]
                if (bodyFatCard != null) {
                    MetricCard(
                        title = bodyFatCard.title,
                        value = bodyFatCard.value,
                        secondaryText = bodyFatCard.unit,
                        status = bodyFatCard.status,
                        onClick = if (isEditing) null else onNavigateToBodyFat,
                        tooltip = bodyFatCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.BLOOD_PRESSURE] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val bpCard = uiState.cardDataMap[CardId.BLOOD_PRESSURE]
                if (bpCard != null) {
                    MetricCard(
                        title = bpCard.title,
                        value = bpCard.value,
                        secondaryText = bpCard.unit,
                        status = bpCard.status,
                        onClick = if (isEditing) null else onNavigateToBloodPressure,
                        tooltip = bpCard.tooltip,
                    )
                }
            },
        )
    }

    cardMap[CardId.OXYGEN_SATURATION] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val spo2Card = uiState.cardDataMap[CardId.OXYGEN_SATURATION]
                if (spo2Card != null) {
                    val spo2Value =
                        if (spo2Card.value == "—") spo2Card.value else "${spo2Card.value}%"
                    MetricCard(
                        title = spo2Card.title,
                        value = spo2Value,
                        secondaryText = spo2Card.secondaryText ?: spo2Card.unit,
                        status = spo2Card.status,
                        onClick = if (isEditing) null else onNavigateToVitals,
                        tooltip = spo2Card.tooltip,
                    )
                }
            },
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
        InsightType.RECOVERY_STAGES_MISSING -> Icons.Default.Info
        InsightType.HRV_DROP_LOW_SPO2 -> Icons.Default.Air
        InsightType.LATE_NADIR_ELEVATED_RHR -> Icons.Default.MonitorHeart
        InsightType.BP_ELEVATED_HIGH_STRAIN -> Icons.Default.Bloodtype
        InsightType.RAS_DEPLETION_HIGH_STRAIN -> Icons.Default.FitnessCenter
        InsightType.HRV_DECLINE_STREAK -> Icons.Default.Warning
        InsightType.STEP_SHORTFALL -> Icons.AutoMirrored.Filled.DirectionsWalk
        InsightType.RAS_WEEKLY_UNDERPERFORMANCE -> Icons.AutoMirrored.Filled.TrendingUp
        InsightType.WEIGHT_DRIFT_TRAINING_LOAD -> Icons.Default.MonitorWeight
    }
