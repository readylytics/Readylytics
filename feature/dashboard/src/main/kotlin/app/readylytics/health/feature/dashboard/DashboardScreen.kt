package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.CardConfigurationsList
import app.readylytics.health.core.ui.components.CardDataMap
import app.readylytics.health.core.ui.components.ReorderableCardGrid
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.insights.InsightParams
import app.readylytics.health.domain.model.InsightType
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    onNavigateToVitals: () -> Unit = {},
    onOpenInsight: (InsightParams) -> Unit = {},
    insightDetail: @Composable (() -> Unit)? = null,
    insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit = { _, _, _, _, _ -> },
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val resolvedError = errorMessage.resolveOrNull()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        if (resolvedError != null) {
            snackbarHostState.showSnackbar(resolvedError)
        }
    }

    DashboardScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::onRefresh,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onDateSelected = { viewModel.onEvent(DashboardEvent.DateSelected(it)) },
        earliestDate = earliestDate,
        onNavigateToSleep = onNavigateToSleep,
        onNavigateToWorkouts = onNavigateToWorkouts,
        onNavigateToRhr = onNavigateToRhr,
        onNavigateToSteps = onNavigateToSteps,
        onNavigateToHeartRate = onNavigateToHeartRate,
        onNavigateToHrv = onNavigateToHrv,
        onNavigateToWeight = onNavigateToWeight,
        onNavigateToBodyFat = onNavigateToBodyFat,
        onNavigateToBloodPressure = onNavigateToBloodPressure,
        onNavigateToVitals = onNavigateToVitals,
        onToggleCardManagement = viewModel::toggleCardManagement,
        onCancelCardManagement = viewModel::onCancelCardManagement,
        onCardVisibilityChanged = viewModel::onToggleCardVisibility,
        onReorderCards = viewModel::onReorderCards,
        onResetToDefaults = viewModel::onResetToDefaults,
        onDismissInsight = { viewModel.onEvent(DashboardEvent.DismissInsight(it)) },
        onRestoreInsights = { viewModel.onEvent(DashboardEvent.RestoreInsights) },
        onOpenInsight = onOpenInsight,
        insightDetail = insightDetail,
        insightsCard = insightsCard,
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
    modifier: Modifier = Modifier,
    onNavigateToHeartRate: () -> Unit = {},
    onNavigateToHrv: () -> Unit = {},
    onNavigateToWeight: () -> Unit = {},
    onNavigateToBodyFat: () -> Unit = {},
    onNavigateToBloodPressure: () -> Unit = {},
    onNavigateToVitals: () -> Unit = {},
    onToggleCardManagement: () -> Unit = {},
    onCancelCardManagement: () -> Unit = {},
    onCardVisibilityChanged: (CardId, Boolean) -> Unit = { _, _ -> },
    onReorderCards: (List<app.readylytics.health.domain.dashboard.CardConfiguration>) -> Unit = {},
    onResetToDefaults: () -> Unit = {},
    onDateSelected: (LocalDate) -> Unit = {},
    earliestDate: LocalDate? = null,
    onDismissInsight: (InsightType) -> Unit = {},
    onRestoreInsights: () -> Unit = {},
    onOpenInsight: (InsightParams) -> Unit = {},
    insightDetail: @Composable (() -> Unit)? = null,
    insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit = { _, _, _, _, _ -> },
) {
    val summary = uiState.summary
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showCardManagement by rememberSaveable { mutableStateOf(false) }
    val today = uiState.today

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
                        onDateSelected = onDateSelected,
                        earliestDate = earliestDate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "date_switcher_spacer") {
                Spacer(modifier = Modifier.height(16.dp))
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
                    // Memoize the card data map so it is only rebuilt when a field the cards
                    // actually render changes. Keying on the single DashboardCardInputs holder
                    // (instead of a multi-key vararg) avoids an Any?[] allocation per
                    // recomposition while still excluding the high-frequency sync fields
                    // (isRefreshing/recalcProgress) that previously forced ReorderableCardGrid
                    // and every child card to recompose each frame during a resync.
                    val cardInputs = uiState.cardInputs()
                    val cardDataMap =
                        remember(cardInputs) {
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
                                    onNavigateToVitals = onNavigateToVitals,
                                    isEditing = uiState.isManagingCards,
                                    isLoading = uiState.isComputingMetrics,
                                    onDismissInsight = onDismissInsight,
                                    onRestoreInsights = onRestoreInsights,
                                    onOpenInsight = onOpenInsight,
                                    insightsCard = insightsCard,
                                ),
                            )
                        }
                    ReorderableCardGrid(
                        cardConfigurations = CardConfigurationsList(uiState.cardConfigurations),
                        cardDataMap = cardDataMap,
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
                        FilledTonalButton(
                            onClick = {
                                showCardManagement = true
                                onToggleCardManagement()
                            },
                            colors =
                                ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
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

        insightDetail?.invoke()
    }
}
