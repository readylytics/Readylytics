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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.scoring.toStatus
import com.gregor.lauritz.healthdashboard.domain.scoring.toTimeString
import com.gregor.lauritz.healthdashboard.ui.components.CardManagementBottomSheet
import com.gregor.lauritz.healthdashboard.ui.components.CircadianConsistencyCard
import com.gregor.lauritz.healthdashboard.ui.components.EditModeIndicator
import com.gregor.lauritz.healthdashboard.ui.components.M3ScoreDial
import com.gregor.lauritz.healthdashboard.ui.components.MetricCard
import com.gregor.lauritz.healthdashboard.ui.components.PaiWeeklyBar
import com.gregor.lauritz.healthdashboard.ui.components.ReorderableCardGrid
import com.gregor.lauritz.healthdashboard.ui.components.StepsBar
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.ui.components.MetricTooltip
import com.gregor.lauritz.healthdashboard.ui.components.StatusLegend
import com.gregor.lauritz.healthdashboard.ui.components.containerColor
import com.gregor.lauritz.healthdashboard.ui.components.onContainerColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
        onToggleCardManagement = viewModel::toggleCardManagement,
        onCardVisibilityChanged = viewModel::onToggleCardVisibility,
        onReorderCards = viewModel::onReorderCards,
        onResetToDefaults = viewModel::onResetToDefaults,
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
    onToggleCardManagement: () -> Unit = {},
    onCardVisibilityChanged: (com.gregor.lauritz.healthdashboard.domain.dashboard.CardId, Boolean) -> Unit = { _, _ -> },
    onReorderCards: (List<com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration>) -> Unit = {},
    onResetToDefaults: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val summary = uiState.summary
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showCardManagement by remember { mutableStateOf(false) }

    if (showCardManagement) {
        CardManagementBottomSheet(
            cards = uiState.cardConfigurations,
            onCardVisibilityChanged = onCardVisibilityChanged,
            onResetToDefaults = onResetToDefaults,
            onDismiss = {
                scope.launch { sheetState.hide() }
                showCardManagement = false
            },
            sheetState = sheetState,
        )
    }

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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    DateSwitcher(
                        selectedDate = uiState.selectedDate,
                        onPreviousDay = onPreviousDay,
                        onNextDay = onNextDay,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                    ReorderableCardGrid(
                        cardConfigurations = uiState.cardConfigurations,
                        cardDataMap = buildCardDataMap(
                            uiState = uiState,
                            onNavigateToSleep = onNavigateToSleep,
                            onNavigateToWorkouts = onNavigateToWorkouts,
                            onNavigateToRhr = onNavigateToRhr,
                            onNavigateToSteps = onNavigateToSteps,
                            isEditing = uiState.isManagingCards,
                        ),
                        isEditing = uiState.isManagingCards,
                        onCardRemove = { cardId ->
                            onCardVisibilityChanged(cardId, false)
                        },
                        onCardReorder = onReorderCards,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
}

            item(key = "spacer_bottom") { Spacer(modifier = Modifier.height(16.dp)) }

            item(key = "status_legend") {
                StatusLegend()
            }

            item(key = "customize_button") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(
                        onClick = {
                            if (!uiState.isManagingCards) {
                                showCardManagement = true
                            }
                            onToggleCardManagement()
                        },
                    ) {
                        Text(
                            text = if (uiState.isManagingCards) "Done" else "Customize",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

