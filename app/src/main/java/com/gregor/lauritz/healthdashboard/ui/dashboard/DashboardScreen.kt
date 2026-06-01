package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.ui.components.CardConfigurationsList
import com.gregor.lauritz.healthdashboard.ui.components.CardDataMap
import com.gregor.lauritz.healthdashboard.ui.components.CardManagementBottomSheet
import com.gregor.lauritz.healthdashboard.ui.components.EditModeFab
import com.gregor.lauritz.healthdashboard.ui.components.ReorderableCardGrid
import com.gregor.lauritz.healthdashboard.ui.components.StatusLegend
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DashboardRoute(
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onNavigateToSteps: () -> Unit,
    onNavigateToHeartRate: () -> Unit = {},
    onNavigateToHrv: () -> Unit = {},
    onNavigateToWeight: () -> Unit = {},
    onNavigateToBodyFat: () -> Unit = {},
    onNavigateToBloodPressure: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage ?: "Error")
        }
    }

    DashboardScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::onRefresh,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onNavigateToSleep = onNavigateToSleep,
        onNavigateToWorkouts = onNavigateToWorkouts,
        onNavigateToRhr = onNavigateToRhr,
        onNavigateToSteps = onNavigateToSteps,
        onNavigateToHeartRate = onNavigateToHeartRate,
        onNavigateToHrv = onNavigateToHrv,
        onNavigateToWeight = onNavigateToWeight,
        onNavigateToBodyFat = onNavigateToBodyFat,
        onNavigateToBloodPressure = onNavigateToBloodPressure,
        onToggleCardManagement = viewModel::toggleCardManagement,
        onCancelCardManagement = viewModel::onCancelCardManagement,
        onCardVisibilityChanged = viewModel::onToggleCardVisibility,
        onReorderCards = viewModel::onReorderCards,
        onResetToDefaults = viewModel::onResetToDefaults,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onNavigateToSleep: () -> Unit,
    onNavigateToWorkouts: () -> Unit,
    onNavigateToRhr: () -> Unit,
    onNavigateToSteps: () -> Unit,
    onNavigateToHeartRate: () -> Unit = {},
    onNavigateToHrv: () -> Unit = {},
    onNavigateToWeight: () -> Unit = {},
    onNavigateToBodyFat: () -> Unit = {},
    onNavigateToBloodPressure: () -> Unit = {},
    onToggleCardManagement: () -> Unit = {},
    onCancelCardManagement: () -> Unit = {},
    onCardVisibilityChanged: (CardId, Boolean) -> Unit = { _, _ -> },
    onReorderCards: (List<com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration>) -> Unit = {},
    onResetToDefaults: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val summary = uiState.summary
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showCardManagement by rememberSaveable { mutableStateOf(false) }
    val today = remember { LocalDate.now(ZoneId.systemDefault()) }

    Box(modifier = modifier.fillMaxSize()) {
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

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("dashboard_lazy_column"),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item(key = "date_switcher") {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                ) {
                    DateSwitcher(
                        selectedDate = uiState.selectedDate,
                        onPreviousDay = onPreviousDay,
                        onNextDay = onNextDay,
                        today = today,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (uiState.isCalibrating) {
                item(key = "calibration_banner") {
                    CalibrationBanner(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            if (summary == null && !uiState.isComputingMetrics && (uiState.selectedDate < today)) {
                item(key = "no_data_placeholder") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_no_data),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "metric_grid") {
                    ReorderableCardGrid(
                        cardConfigurations = CardConfigurationsList(uiState.cardConfigurations),
                        cardDataMap =
                            CardDataMap(
                                buildCardDataMap(
                                    uiState = uiState,
                                    onNavigateToSleep = onNavigateToSleep,
                                    onNavigateToWorkouts = onNavigateToWorkouts,
                                    onNavigateToRhr = onNavigateToRhr,
                                    onNavigateToSteps = onNavigateToSteps,
                                    onNavigateToHeartRate = onNavigateToHeartRate,
                                    onNavigateToHrv = onNavigateToHrv,
                                    onNavigateToWeight = onNavigateToWeight,
                                    onNavigateToBodyFat = onNavigateToBodyFat,
                                    onNavigateToBloodPressure = onNavigateToBloodPressure,
                                    isEditing = uiState.isManagingCards,
                                    isLoading = uiState.isComputingMetrics,
                                ),
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

            if (!uiState.isManagingCards) {
                item(key = "customize_button") {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(
                            onClick = {
                                showCardManagement = true
                                onToggleCardManagement()
                            },
                        ) {
                            Text(
                                text = stringResource(R.string.action_customize),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = if (uiState.isManagingCards) 88.dp else 16.dp,
                    ),
            snackbar = { data ->
                Snackbar(
                    data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            },
        )

        EditModeFab(
            isVisible = uiState.isManagingCards,
            onDoneClick = onToggleCardManagement,
            onCancelClick = onCancelCardManagement,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}
