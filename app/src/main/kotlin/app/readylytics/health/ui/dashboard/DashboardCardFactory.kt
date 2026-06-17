package app.readylytics.health.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.readylytics.health.R
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.insights.detail.DailyInsightContext
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.ui.common.CardLoader
import app.readylytics.health.ui.common.MetricCardSkeleton
import app.readylytics.health.ui.common.ScoreDialSkeleton
import app.readylytics.health.ui.components.CircadianConsistencyCard
import app.readylytics.health.ui.components.InsightCard
import app.readylytics.health.ui.components.InsightRerunCard
import app.readylytics.health.ui.components.M3ScoreDial
import app.readylytics.health.ui.components.SoftArcMetricCard
import app.readylytics.health.ui.components.StepsCard
import app.readylytics.health.ui.heartrate.HeartRateCard
import app.readylytics.health.ui.insights.InsightDetailRepository
import app.readylytics.health.ui.insights.InsightDetailSheet

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
): Map<CardId, @Composable () -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable () -> Unit>()

    val summary = uiState.summary

    @Composable
    fun metricCard(
        card: CardData,
        onClick: (() -> Unit)?,
        valueOverride: String? = null,
        unitOverride: String? = null,
    ) {
        SoftArcMetricCard(
            title = card.title,
            value = valueOverride ?: card.value,
            unit = unitOverride ?: card.unit,
            status = card.status,
            tooltip = card.tooltip,
            progress = card.softArcProgress(),
            baselineDeltaText = card.baselineDeltaText,
            baselineDeltaDirection = card.baselineDeltaDirection,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    cardMap[CardId.SLEEP_SCORE] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { ScoreDialSkeleton() },
            content = {
                val sleepScoreCard = uiState.cardDataMap[CardId.SLEEP_SCORE]
                M3ScoreDial(
                    label = "Sleep Score",
                    score = summary?.sleepScore,
                    displayText = sleepScoreCard?.value ?: "—",
                    status = sleepScoreCard?.status,
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
                M3ScoreDial(
                    label = "Readiness",
                    score = readinessCard?.value?.toFloatOrNull(),
                    displayText = readinessCard?.value ?: "—",
                    status = readinessCard?.status,
                    onClick = if (isEditing) ({}) else onNavigateToWorkouts,
                    tooltipDescription = readinessCard?.tooltip,
                )
            },
        )
    }

    if (uiState.activeInsightTypes.isNotEmpty() || isEditing) {
        cardMap[CardId.INSIGHTS] = {
            var selectedInsightForDetails by remember { mutableStateOf<InsightType?>(null) }
            val context = LocalContext.current
            val detailRepository = remember { InsightDetailRepository(context.resources) }
            val detailContext =
                remember(
                    uiState.summary,
                    uiState.stepGoal,
                    uiState.goalSleepHours,
                    uiState.selectedDate,
                    uiState.userPreferences,
                ) {
                    uiState.toDailyInsightContext()
                }

            AnimatedContent(
                targetState = uiState.currentInsight,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "dashboard_insight_card",
            ) { insight ->
                if (insight != null) {
                    val detail = detailRepository.getDetail(insight, detailContext, uiState.currentInsightParams)
                    val bodyText =
                        if (insight == InsightType.REST_DAY_SUCCESS) {
                            val sleepScore = uiState.summary?.sleepScore ?: 0f
                            val duration = uiState.summary?.sleepDurationMinutes ?: 0
                            val isPerfectSleep = sleepScore >= 85f && duration >= (uiState.goalSleepHours * 60).toInt()
                            if (isPerfectSleep) {
                                detail.cardDescription + stringResource(R.string.insight_rest_day_perfect_sleep)
                            } else {
                                detail.cardDescription
                            }
                        } else {
                            detail.cardDescription
                        }

                    InsightCard(
                        title = detail.title,
                        body = bodyText,
                        icon = getInsightIcon(insight),
                        onDismiss = { onDismissInsight(insight) },
                        onShowDetails = { selectedInsightForDetails = insight },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    InsightRerunCard(
                        text =
                            if (isEditing) {
                                stringResource(R.string.card_title_insights)
                            } else {
                                stringResource(
                                    R.string.insight_restore_dismissed,
                                    uiState.dismissedInsightCount,
                                )
                            },
                        icon = if (isEditing) Icons.Default.Info else Icons.Default.Refresh,
                        onRestore = if (isEditing) ({}) else onRestoreInsights,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            selectedInsightForDetails?.let { selected ->
                InsightDetailSheet(
                    content = detailRepository.getDetail(selected, detailContext, uiState.currentInsightParams),
                    onDismiss = { selectedInsightForDetails = null },
                )
            }
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
                    metricCard(
                        card = hrvCard,
                        onClick = if (isEditing) null else onNavigateToHrv,
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
                    metricCard(
                        card = sleepRhrCard,
                        onClick = if (isEditing) null else onNavigateToSleep,
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
                    metricCard(
                        card = strainCard,
                        onClick = if (isEditing) null else onNavigateToWorkouts,
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
                    metricCard(
                        card = durationCard,
                        onClick = if (isEditing) null else onNavigateToSleep,
                        unitOverride = durationCard.secondaryText ?: durationCard.unit,
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
                    metricCard(
                        card = efficiencyCard,
                        onClick = if (isEditing) null else onNavigateToSleep,
                        unitOverride = efficiencyCard.secondaryText ?: efficiencyCard.unit,
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
                    metricCard(
                        card = rasCard,
                        onClick = if (isEditing) null else onNavigateToWorkouts,
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
                    metricCard(
                        card = card,
                        onClick = if (isEditing) null else onNavigateToRhr,
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
                    CircadianConsistencyCard(
                        result = uiState.circadianConsistency,
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
                    metricCard(
                        card = weightCard,
                        onClick = if (isEditing) null else onNavigateToWeight,
                        unitOverride = weightCard.secondaryText ?: weightCard.unit,
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
                    metricCard(
                        card = bodyFatCard,
                        onClick = if (isEditing) null else onNavigateToBodyFat,
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
                    metricCard(
                        card = bpCard,
                        onClick = if (isEditing) null else onNavigateToBloodPressure,
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
                    metricCard(
                        card = spo2Card,
                        valueOverride = spo2Value,
                        unitOverride = spo2Card.secondaryText ?: spo2Card.unit,
                        onClick = if (isEditing) null else onNavigateToVitals,
                    )
                }
            },
        )
    }

    return cardMap
}

private fun CardData.softArcProgress(): Float =
    value
        .filter { it.isDigit() || it == '.' }
        .toFloatOrNull()
        ?.let { numeric ->
            when {
                unit == "%" -> numeric / 100f
                title.contains("strain", ignoreCase = true) -> numeric / 2f
                title.contains("blood", ignoreCase = true) -> numeric / 180f
                title.contains("weight", ignoreCase = true) -> 0.5f
                title.contains("body", ignoreCase = true) -> numeric / 40f
                title.contains("oxygen", ignoreCase = true) -> numeric / 100f
                title.contains("duration", ignoreCase = true) -> 0.65f
                title.contains("efficiency", ignoreCase = true) -> numeric / 100f
                title.contains("ras", ignoreCase = true) -> numeric / 100f
                title.contains("hrv", ignoreCase = true) -> numeric / 120f
                title.contains("heart", ignoreCase = true) -> numeric / 120f
                else -> numeric / 100f
            }.coerceIn(0f, 1f)
        } ?: 0f

private fun DashboardUiState.toDailyInsightContext(): DailyInsightContext =
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

private fun getInsightIcon(type: InsightType): ImageVector =
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
