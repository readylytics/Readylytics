package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.scoring.toStatus
import com.gregor.lauritz.healthdashboard.domain.scoring.toTimeString
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.components.PaiWeeklyBar
import com.gregor.lauritz.healthdashboard.ui.components.StepsBar
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.containerColor
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SLEEP_TOOLTIP = "Total quality of rest based on duration and cycles.\n\n" +
    "• 80–100: Optimal\n" +
    "• 60–79: Fair\n" +
    "• < 60: Poor"

private const val READINESS_TOOLTIP = "Preparation for stress based on recent load & recovery.\n\n" +
    "• 85–100: Peak\n" +
    "• 30–69: Moderate\n" +
    "• < 30: Rest"

@Composable
fun DashboardRoute(
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onNavigateToSteps: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    DashboardScreen(
        uiState = uiState,
        today = today,
        onRefresh = viewModel::onRefresh,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onNavigateToSleep = onNavigateToSleep,
        onNavigateToWorkouts = onNavigateToWorkouts,
        onNavigateToRhr = onNavigateToRhr,
        onNavigateToSteps = onNavigateToSteps,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    today: LocalDate,
    onRefresh: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onNavigateToSteps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = uiState.summary
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item(key = "date_switcher") {
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                )
            }

            item(key = "hero_scores") {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    M3ScoreDial(
                        score = summary?.sleepScore,
                        label = "Sleep Score",
                        onClick = onNavigateToSleep,
                        tooltipDescription = SLEEP_TOOLTIP,
                    )
                    M3ScoreDial(
                        score = summary?.readinessScore,
                        label = "Readiness",
                        onClick = onNavigateToWorkouts,
                        tooltipDescription = READINESS_TOOLTIP,
                    )
                }
            }

            item(key = "spacer_hero") { Spacer(modifier = Modifier.height(8.dp)) }

            item(key = "steps_card") {
                DashboardStepsCard(
                    stepCount = uiState.stepCount,
                    stepGoal = uiState.stepGoal,
                    onClick = onNavigateToSteps,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item(key = "spacer_steps") { Spacer(modifier = Modifier.height(8.dp)) }

            item(key = "section_label") {
                val sectionLabel =
                    remember(uiState.selectedDate, today) {
                        when (uiState.selectedDate) {
                            today -> "Last Night"
                            today.minusDays(1) -> "Night of Yesterday"
                            else -> {
                                val pattern =
                                    DateTimeFormatter
                                        .ofPattern("EEE MMM d", Locale.getDefault())
                                "Night of ${uiState.selectedDate.format(pattern)}"
                            }
                        }
                    }
                Text(
                    text = sectionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (summary == null && (uiState.selectedDate < today)) {
                item(key = "no_data_placeholder") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No data for this day",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "metric_grid") {
                    MetricCardGrid(
                        rows = uiState.cardRows,
                        onNavigateToSleep = onNavigateToSleep,
                        onNavigateToWorkouts = onNavigateToWorkouts,
                        onNavigateToRhr = onNavigateToRhr,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item(key = "bottom_cards") {
                    if (uiState.restingHrCard != null || uiState.circadianConsistency != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (uiState.restingHrCard != null) {
                                val card = uiState.restingHrCard
                                MetricCard(
                                    title = card.title,
                                    value = card.value,
                                    secondaryText = card.unit,
                                    status = card.status,
                                    onClick = onNavigateToRhr,
                                    tooltip = card.tooltip,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }

                            if (uiState.circadianConsistency != null) {
                                DashboardCircadianCard(
                                    result = uiState.circadianConsistency,
                                    onNavigateToSleep = onNavigateToSleep,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            item(key = "spacer_bottom") { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DashboardCircadianCard(
    result: CircadianConsistencyResult,
    onNavigateToSleep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = result.toStatus()
    val containerColor = status.containerColor()
    val contentColor = status.onContainerColor()

    val scoreText = when (result) {
        is CircadianConsistencyResult.Calibrating -> "Calibrating"
        is CircadianConsistencyResult.Ready -> "${result.score.toInt()}%"
    }
    val windowText = when (result) {
        is CircadianConsistencyResult.Calibrating -> ""
        is CircadianConsistencyResult.Ready ->
            "${result.medianBedtimeMinutes.toTimeString()}→${result.medianWakeMinutes.toTimeString()}"
    }

    val tooltipText = remember(result) {
        val thresholdMinutes = when (result) {
            is CircadianConsistencyResult.Calibrating -> 30
            is CircadianConsistencyResult.Ready -> result.thresholdMinutes
        }
        buildString {
            append("Measures how regular your sleep schedule is.\n\n")
            append("High consistency stabilizes your internal clock, improving deep sleep and energy levels.\n\n")
            append("• ≥ 80%: Optimal\n")
            append("• 60–79%: Neutral\n")
            append("• 40–59%: Warning\n")
            append("• < 40%: Poor\n\n")
            append("Consistency Window: ±$thresholdMinutes min grace period before score drops.")
        }
    }

    Card(
        onClick = onNavigateToSleep,
        modifier = modifier.semantics { role = Role.Button },
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Circadian Consistency",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                )
                MetricTooltip(description = tooltipText, iconTint = contentColor)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = scoreText,
                style = MaterialTheme.typography.displaySmall,
                color = contentColor,
            )
            Text(
                text = windowText,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun DashboardStepsCard(
    stepCount: Int?,
    stepGoal: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics { role = Role.Button },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Daily Steps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Goal: ${java.text.NumberFormat.getNumberInstance().format(stepGoal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            StepsBar(
                stepCount = stepCount,
                stepGoal = stepGoal,
            )
        }
    }
}

@Composable
private fun MetricCardGrid(
    rows: List<List<CardData>>,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToRhr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { card ->
                    val onClick =
                        remember(card.action, onNavigateToSleep, onNavigateToWorkouts, onNavigateToRhr) {
                            when (card.action) {
                                DashboardAction.NAVIGATE_SLEEP -> onNavigateToSleep
                                DashboardAction.NAVIGATE_WORKOUTS -> onNavigateToWorkouts
                                DashboardAction.NAVIGATE_RHR -> onNavigateToRhr
                                DashboardAction.NAVIGATE_STEPS -> null
                                null -> null
                            }
                        }
                    MetricCard(
                        title = card.title,
                        value = card.value,
                        secondaryText = card.unit,
                        status = card.status,
                        onClick = onClick,
                        tooltip = card.tooltip,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
