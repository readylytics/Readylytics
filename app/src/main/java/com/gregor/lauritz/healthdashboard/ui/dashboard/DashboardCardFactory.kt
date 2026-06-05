package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.ui.common.CardLoader
import com.gregor.lauritz.healthdashboard.ui.common.MetricCardSkeleton
import com.gregor.lauritz.healthdashboard.ui.common.ScoreDialSkeleton
import com.gregor.lauritz.healthdashboard.ui.components.CircadianConsistencyCard
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.components.StepsCard
import com.gregor.lauritz.healthdashboard.ui.heartrate.HeartRateCard

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
): Map<CardId, @Composable () -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable () -> Unit>()

    val summary = uiState.summary

    cardMap[CardId.SLEEP_SCORE] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { ScoreDialSkeleton() },
            content = {
                M3ScoreDial(
                    label = "Sleep Score",
                    score = summary?.sleepScore,
                    onClick = if (isEditing) ({}) else onNavigateToSleep,
                    tooltipDescription =
                        "Total quality of rest based on duration and cycles.\n\n• 80–100: Optimal\n• 60–79: Fair\n• < 60: Poor",
                )
            },
        )
    }

    cardMap[CardId.READINESS] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { ScoreDialSkeleton() },
            content = {
                M3ScoreDial(
                    label = "Readiness",
                    score = summary?.readinessScore,
                    onClick = if (isEditing) ({}) else onNavigateToWorkouts,
                    tooltipDescription = stringResource(R.string.tooltip_readiness),
                )
            },
        )
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

    cardMap[CardId.PAI_DAILY] = {
        CardLoader(
            isLoading = isLoading,
            skeleton = { MetricCardSkeleton() },
            content = {
                val paiCard = uiState.cardDataMap[CardId.PAI_DAILY]
                if (paiCard != null) {
                    MetricCard(
                        title = paiCard.title,
                        value = paiCard.value,
                        secondaryText = paiCard.unit,
                        status = paiCard.status,
                        onClick = if (isEditing) null else onNavigateToWorkouts,
                        tooltip = paiCard.tooltip,
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
