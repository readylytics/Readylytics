package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
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
        onNavigateToSleep = onNavigateToSleep,
        onNavigateToWorkouts = onNavigateToWorkouts,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    onRefresh: () -> Unit,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
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
            item {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    M3ScoreDial(
                        score = summary?.sleepScore,
                        label = "Sleep Score",
                        onClick = onNavigateToSleep,
                    )
                    M3ScoreDial(
                        score = summary?.loadScore,
                        label = "Load Score",
                        displayText = summary?.strainRatio?.let { "%.2f".format(it) },
                        onClick = onNavigateToWorkouts,
                        tooltipDescription =
                            buildString {
                                append("Strain Ratio = 7-day avg TRIMP ÷ 42-day avg TRIMP\n\n")
                                append("• < 0.8: Under-trained\n")
                                append("• 0.8–1.2: Optimal\n")
                                append("• 1.2–1.5: Fatiguing\n")
                                append("• > 1.5: Over-reached")
                            },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                Text(
                    text = "Last Night",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item {
                val rhrStatus =
                    summary?.rhrStatus(
                        uiState.rhrOptimalThreshold,
                        uiState.rhrWarningThreshold,
                    ) ?: MetricStatus.CALIBRATING
                val hrvStatus =
                    summary?.hrvStatus(
                        uiState.hrvOptimalThreshold,
                        uiState.hrvWarningThreshold,
                    ) ?: MetricStatus.CALIBRATING
                val durationStatus =
                    summary?.sleepDurationStatus(uiState.goalSleepMinutes) ?: MetricStatus.CALIBRATING

                val rhrBaseline =
                    summary?.let { s ->
                        val ratio = s.rhrRatio
                        val rhr = s.nocturnalRhr
                        if (ratio != null && ratio > 0f && rhr != null) {
                            (rhr / ratio).toInt()
                        } else {
                            null
                        }
                    }

                val hrvBaseline = summary?.hrvBaseline?.toDouble()

                val rhrDiff =
                    summary?.let { s ->
                        val ratio = s.rhrRatio
                        val rhr = s.nocturnalRhr
                        if (ratio != null && ratio > 0f && rhr != null) {
                            val baseline = (rhr / ratio).toInt()
                            kotlin.math.abs(rhr.toInt() - baseline)
                        } else {
                            null
                        }
                    }

                val hrvDiff =
                    summary?.let { s ->
                        val baseline = s.hrvBaseline
                        val hrv = s.nocturnalHrv
                        if (baseline != null && hrv != null) {
                            kotlin.math.abs(hrv - baseline)
                        } else {
                            null
                        }
                    }

                val rhrArrow =
                    if (rhrBaseline != null && summary?.nocturnalRhr != null) {
                        if (summary.nocturnalRhr > rhrBaseline) {
                            "↑"
                        } else {
                            "↓"
                        }
                    } else {
                        null
                    }

                val hrvArrow =
                    if (hrvBaseline != null && summary?.nocturnalHrv != null) {
                        if (summary.nocturnalHrv > hrvBaseline) {
                            "↑"
                        } else {
                            "↓"
                        }
                    } else {
                        null
                    }

                val cards =
                    listOf(
                        CardData(
                            title = "Sleep RHR",
                            value = summary?.nocturnalRhr?.toInt()?.toString() ?: "—",
                            unit = "bpm",
                            status = rhrStatus,
                            onClick = onNavigateToSleep,
                            tooltip =
                                buildString {
                                    if (rhrBaseline != null && rhrArrow != null && rhrDiff != null) {
                                        append("Baseline: $rhrBaseline bpm $rhrArrow ($rhrDiff bpm)")
                                        append(
                                            "\n\nCompare nocturnal resting heart rate to your personal 30-day baseline.",
                                        )
                                    } else {
                                        append(
                                            "Comparison of nocturnal resting heart rate to your personal 30-day baseline.",
                                        )
                                        append("\n\nNot enough data to calculate baseline.")
                                    }
                                },
                        ),
                        CardData(
                            title = "Sleep HRV",
                            value = summary?.nocturnalHrv?.toInt()?.toString() ?: "—",
                            unit = "ms",
                            status = hrvStatus,
                            onClick = onNavigateToSleep,
                            tooltip =
                                buildString {
                                    if (hrvBaseline != null && hrvArrow != null && hrvDiff != null) {
                                        append(
                                            "Baseline: ${"%.0f".format(hrvBaseline)} ms $hrvArrow" +
                                                " (${"%.0f".format(hrvDiff)} ms)",
                                        )
                                        append(
                                            "\n\nCompare your Heart Rate Variability to your unique 30-day normal range.",
                                        )
                                    } else {
                                        append(
                                            "Your Heart Rate Variability compared to your unique 30-day normal range.",
                                        )
                                        append("\n\nNot enough data to calculate baseline.")
                                    }
                                },
                        ),
                        CardData(
                            title = "Sleep Duration",
                            value = formatSleepDuration(summary?.sleepDurationMinutes),
                            unit = "",
                            status = durationStatus,
                            onClick = onNavigateToSleep,
                            tooltip =
                                buildString {
                                    append("Total time asleep last night.")
                                    append("\n\nGoal: ${formatSleepDuration(uiState.goalSleepMinutes)}")
                                },
                        ),
                    )

                MetricCardGrid(
                    cards = cards,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MetricCardGrid(
    cards: List<CardData>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { card ->
                    MetricCard(
                        title = card.title,
                        value = card.value,
                        unit = card.unit,
                        status = card.status,
                        onClick = card.onClick,
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
    onClick: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
) {
    val containerColor = status.containerColor()
    val contentColor = status.onContainerColor()

    Card(
        onClick = onClick,
        modifier =
            modifier.semantics {
                role = Role.Button
            },
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

private data class CardData(
    val title: String,
    val value: String,
    val unit: String,
    val status: MetricStatus,
    val onClick: () -> Unit,
    val tooltip: String,
)

private fun formatSleepDuration(minutes: Int?): String {
    if (minutes == null) return "—"
    val hours = minutes / 60
    val mins = minutes % 60
    return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
}
