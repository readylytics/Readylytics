package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.ui.components.CircadianConsistencyCard
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.PaiWeeklyBar
import com.gregor.lauritz.healthdashboard.ui.components.StepsCard

// Build a map of CardId to composable card content for the Dashboard screen
// This factory method creates all available dashboard cards and maps them by ID
// for use with the ReorderableCardGrid component
fun buildCardDataMap(
    uiState: DashboardUiState,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onNavigateToSteps: () -> Unit = {},
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

    cardMap[CardId.STEPS] = {
        StepsCard(
            stepCount = uiState.stepCount,
            stepGoal = uiState.stepGoal,
            onClick = onNavigateToSteps,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    cardMap[CardId.HRV] = {
        val hrvCard = uiState.cardDataMap[CardId.HRV]
        if (hrvCard != null) {
            MetricCard(
                title = hrvCard.title,
                value = hrvCard.value,
                secondaryText = hrvCard.unit,
                status = hrvCard.status,
                onClick = onNavigateToRhr,
                tooltip = hrvCard.tooltip,
            )
        }
    }

    cardMap[CardId.SLEEP_RHR] = {
        val sleepRhrCard = uiState.cardDataMap[CardId.SLEEP_RHR]
        if (sleepRhrCard != null) {
            MetricCard(
                title = sleepRhrCard.title,
                value = sleepRhrCard.value,
                secondaryText = sleepRhrCard.unit,
                status = sleepRhrCard.status,
                onClick = onNavigateToSleep,
                tooltip = sleepRhrCard.tooltip,
            )
        }
    }

    cardMap[CardId.STRAIN_RATIO] = {
        val strainCard = uiState.cardDataMap[CardId.STRAIN_RATIO]
        if (strainCard != null) {
            MetricCard(
                title = strainCard.title,
                value = strainCard.value,
                secondaryText = strainCard.unit,
                status = strainCard.status,
                onClick = onNavigateToWorkouts,
                tooltip = strainCard.tooltip,
            )
        }
    }

    cardMap[CardId.SLEEP_DURATION] = {
        val durationCard = uiState.cardDataMap[CardId.SLEEP_DURATION]
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

    cardMap[CardId.PAI_DAILY] = {
        val paiCard = uiState.cardDataMap[CardId.PAI_DAILY]
        if (paiCard != null) {
            Card(
                onClick = onNavigateToWorkouts,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { role = Role.Button }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = paiCard.title,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        MetricTooltip(description = paiCard.tooltip)
                    }
                    Spacer(Modifier.height(12.dp))
                    PaiWeeklyBar(
                        dailyBreakdown = uiState.paiDailyBreakdown,
                        totalPai = summary?.totalPai ?: 0f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    cardMap[CardId.RESTING_HR] = {
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

    cardMap[CardId.CIRCADIAN_CONSISTENCY] = {
        if (uiState.circadianConsistency != null) {
            CircadianConsistencyCard(
                result = uiState.circadianConsistency,
                onClick = onNavigateToSleep,
            )
        }
    }

    return cardMap
}
