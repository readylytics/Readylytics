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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.containerColor
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DashboardRoute(
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    DashboardScreen(
        uiState = uiState,
        onRefresh = viewModel::onRefresh,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onNavigateToSleep = onNavigateToSleep,
        onNavigateToWorkouts = onNavigateToWorkouts,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = uiState.summary
    val today = LocalDate.now()

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                )
            }

            item {
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
                        tooltipDescription =
                            buildString {
                                append("Total quality of rest based on duration and cycles.\n\n")
                                append("• 80–100: Optimal\n")
                                append("• 60–79: Fair\n")
                                append("• < 60: Poor")
                            },
                    )
                    M3ScoreDial(
                        score = summary?.readinessScore,
                        label = "Readiness",
                        onClick = onNavigateToWorkouts,
                        tooltipDescription =
                            buildString {
                                append("Preparation for stress based on recent load & recovery.\n\n")
                                append("• 85–100: Peak\n")
                                append("• 30–69: Moderate\n")
                                append("• < 30: Rest")
                            },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                val sectionLabel =
                    remember(uiState.selectedDate) {
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

            if (summary == null &&
                uiState.selectedDate < today
            ) {
                item {
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
                item {
                    MetricCardGrid(
                        cards = uiState.cardData,
                        onNavigateToSleep = onNavigateToSleep,
                        onNavigateToWorkouts = onNavigateToWorkouts,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MetricCardGrid(
    cards: List<com.gregor.lauritz.healthdashboard.ui.dashboard.CardData>,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { card ->
                    val onClick =
                        when (card.action) {
                            DashboardAction.NAVIGATE_SLEEP -> onNavigateToSleep
                            DashboardAction.NAVIGATE_WORKOUTS -> onNavigateToWorkouts
                            null -> null
                        }
                    MetricCard(
                        title = card.title,
                        value = card.value,
                        unit = card.unit,
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

@Composable
private fun MetricCard(
    title: String,
    value: String,
    unit: String,
    status: MetricStatus,
    onClick: (() -> Unit)?,
    tooltip: String,
    modifier: Modifier = Modifier,
) {
    val containerColor = status.containerColor()
    val contentColor = status.onContainerColor()

    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier =
            modifier.let {
                if (onClick != null) {
                    it.semantics {
                        role = Role.Button
                    }
                } else {
                    it
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor,
                disabledContentColor = contentColor,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                )
                MetricTooltip(description = tooltip, iconTint = contentColor)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = contentColor,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
    }
}

private fun formatSleepDuration(minutes: Int?): String {
    if (minutes == null) return "—"
    val hours = minutes / 60
    val mins = minutes % 60
    return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
}
