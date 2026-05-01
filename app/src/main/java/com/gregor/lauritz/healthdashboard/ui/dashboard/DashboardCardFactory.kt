package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.runtime.Composable
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.ui.components.CircadianConsistencyCard
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard

fun buildCardDataMap(
    uiState: DashboardUiState,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToRhr: () -> Unit,
): Map<CardId, @Composable () -> Unit> {
    val cardMap = mutableMapOf<CardId, @Composable () -> Unit>()

    val summary = uiState.summary

    cardMap[CardId.SLEEP_SCORE] = {
        M3ScoreDial(
            score = summary?.sleepScore,
            label = "Sleep Score",
            onClick = onNavigateToSleep,
            tooltipDescription = "Total quality of rest based on duration and cycles.\n\n• 80–100: Optimal\n• 60–79: Fair\n• < 60: Poor",
        )
    }

    cardMap[CardId.READINESS] = {
        M3ScoreDial(
            score = summary?.readinessScore,
            label = "Readiness",
            onClick = onNavigateToWorkouts,
            tooltipDescription = "Preparation for stress based on recent load & recovery.\n\n• 85–100: Peak\n• 30–69: Moderate\n• < 30: Rest",
        )
    }

    cardMap[CardId.HRV] = {
        val hrvCard = uiState.cardData.find { it.title.contains("HRV", ignoreCase = true) }
        if (hrvCard != null) {
            MetricCard(
                title = hrvCard.title,
                value = hrvCard.value,
                secondaryText = hrvCard.unit,
                status = hrvCard.status,
                onClick = null,
                tooltip = hrvCard.tooltip,
            )
        }
    }

    cardMap[CardId.RHR] = {
        if (uiState.restingHrCard != null) {
            val card = uiState.restingHrCard
            MetricCard(
                title = card.title,
                value = card.value,
                secondaryText = card.unit,
                status = card.status,
                onClick = onNavigateToRhr,
                tooltip = card.tooltip,
            )
        }
    }

    cardMap[CardId.LOAD_SCORE] = {
        val loadCard = uiState.cardData.find { it.title.contains("Load", ignoreCase = true) }
        if (loadCard != null) {
            MetricCard(
                title = loadCard.title,
                value = loadCard.value,
                secondaryText = loadCard.unit,
                status = loadCard.status,
                onClick = null,
                tooltip = loadCard.tooltip,
            )
        }
    }

    cardMap[CardId.STRAIN_RATIO] = {
        val strainCard = uiState.cardData.find { it.title.contains("Strain", ignoreCase = true) }
        if (strainCard != null) {
            MetricCard(
                title = strainCard.title,
                value = strainCard.value,
                secondaryText = strainCard.unit,
                status = strainCard.status,
                onClick = null,
                tooltip = strainCard.tooltip,
            )
        }
    }

    cardMap[CardId.SLEEP_DURATION] = {
        val durationCard = uiState.cardData.find { it.title.contains("Duration", ignoreCase = true) }
        if (durationCard != null) {
            MetricCard(
                title = durationCard.title,
                value = durationCard.value,
                secondaryText = durationCard.unit,
                status = durationCard.status,
                onClick = onNavigateToSleep,
                tooltip = durationCard.tooltip,
            )
        }
    }

    cardMap[CardId.CIRCADIAN_CONSISTENCY] = {
        if (uiState.circadianConsistency != null) {
            CircadianConsistencyCard(
                result = uiState.circadianConsistency,
                onClick = onNavigateToSleep,
            )
        }
    }

    return cardMap.filterValues { it != null }
}
