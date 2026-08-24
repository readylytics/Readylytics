package app.readylytics.health.feature.dashboard

import android.content.ClipData
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.scoring.domain.insights.InsightParams
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.CardConfigurationsList
import app.readylytics.health.core.ui.components.CardDataMap
import app.readylytics.health.core.ui.components.ReorderableCardGrid
import app.readylytics.health.core.ui.components.rememberManageLayoutState
import kotlinx.coroutines.launch
import java.time.LocalDate

internal data class ColoredSnackbarVisuals(
    override val message: String,
    val isError: Boolean,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

data class DashboardNavigationCallbacks(
    val onNavigateToSleep: () -> Unit = {},
    val onNavigateToWorkouts: () -> Unit = {},
    val onNavigateToRhr: () -> Unit = {},
    val onNavigateToSteps: () -> Unit = {},
    val onNavigateToHeartRate: () -> Unit = {},
    val onNavigateToHrv: () -> Unit = {},
    val onNavigateToWeight: () -> Unit = {},
    val onNavigateToBodyFat: () -> Unit = {},
    val onNavigateToBloodPressure: () -> Unit = {},
    val onNavigateToVitals: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardManagementSheet(
    isOpen: Boolean,
    cardConfigurations: List<app.readylytics.health.core.model.domain.dashboard.CardConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
) {
    if (isOpen) {
        CardManagementBottomSheet(
            cards = cardConfigurations,
            onCardVisibilityChanged = onCardVisibilityChanged,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
            onResetToDefaults = onResetToDefaults,
            onDismiss = onDismiss,
            sheetState = sheetState,
        )
    }
}

@Composable
internal fun MetricGridSection(
    uiState: DashboardUiState,
    navigationCallbacks: DashboardNavigationCallbacks,
    isEditing: Boolean,
    isLoading: Boolean,
    onDismissInsight: (InsightType) -> Unit,
    onRestoreInsights: () -> Unit,
    onOpenInsight: (InsightParams) -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
    onCopySetupPrompt: () -> Unit,
    onCopyDailyPrompt: () -> Unit,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onReorderCards: (List<app.readylytics.health.core.model.domain.dashboard.CardConfiguration>) -> Unit,
    insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit,
) {
    val cardInputs = uiState.cardInputs()
    val cardDataMap =
        remember(cardInputs) {
            CardDataMap(
                buildCardDataMap(
                    uiState = uiState,
                    onNavigateToSleep = navigationCallbacks.onNavigateToSleep,
                    onNavigateToWorkouts = navigationCallbacks.onNavigateToWorkouts,
                    onNavigateToRhr = navigationCallbacks.onNavigateToRhr,
                    onNavigateToSteps = navigationCallbacks.onNavigateToSteps,
                    onNavigateToHeartRate = navigationCallbacks.onNavigateToHeartRate,
                    onNavigateToHrv = navigationCallbacks.onNavigateToHrv,
                    onNavigateToWeight = navigationCallbacks.onNavigateToWeight,
                    onNavigateToBodyFat = navigationCallbacks.onNavigateToBodyFat,
                    onNavigateToBloodPressure = navigationCallbacks.onNavigateToBloodPressure,
                    onNavigateToVitals = navigationCallbacks.onNavigateToVitals,
                    isEditing = isEditing,
                    isLoading = isLoading,
                    onDismissInsight = onDismissInsight,
                    onRestoreInsights = onRestoreInsights,
                    onOpenInsight = onOpenInsight,
                    onCardDisplayModeChanged = onCardDisplayModeChanged,
                    onCopySetupPrompt = onCopySetupPrompt,
                    onCopyDailyPrompt = onCopyDailyPrompt,
                    insightsCard = insightsCard,
                ),
            )
        }
    ReorderableCardGrid(
        cardConfigurations = CardConfigurationsList(uiState.cardConfigurations),
        cardDataMap = cardDataMap,
        isEditing = isEditing,
        onCardRemove = { cardId ->
            onCardVisibilityChanged(cardId, false)
        },
        onCardReorder = onReorderCards,
        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    )
}

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
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboard.current
    val copiedMessage = stringResource(R.string.ai_recommendation_copied_snackbar)
    val setupPrompt = stringResource(R.string.ai_init_prompt)
    val clipLabel = stringResource(R.string.ai_recommendation_clip_label)
    val dailyPromptText by viewModel.dailyPromptText.collectAsStateWithLifecycle()

    LaunchedEffect(errorMessage) {
        if (resolvedError != null) {
            snackbarHostState.showSnackbar(ColoredSnackbarVisuals(resolvedError, isError = true))
        }
    }

    LaunchedEffect(dailyPromptText) {
        dailyPromptText?.let { prompt ->
            clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, prompt.text)))
            snackbarHostState.showSnackbar(ColoredSnackbarVisuals(copiedMessage, isError = false))
            viewModel.clearDailyPromptText()
        }
    }

    DashboardScreen(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onDateSelected = { viewModel.onEvent(DashboardEvent.DateSelected(it)) },
        earliestDate = earliestDate,
        navigationCallbacks =
            DashboardNavigationCallbacks(
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
            ),
        onToggleCardManagement = viewModel::toggleCardManagement,
        onCancelCardManagement = viewModel::onCancelCardManagement,
        onCardVisibilityChanged = viewModel::onToggleCardVisibility,
        onReorderCards = viewModel::onReorderCards,
        onResetToDefaults = viewModel::onResetToDefaults,
        onCardDisplayModeChanged = viewModel::onCardDisplayModeChanged,
        onDismissInsight = { viewModel.onEvent(DashboardEvent.DismissInsight(it)) },
        onRestoreInsights = { viewModel.onEvent(DashboardEvent.RestoreInsights) },
        onOpenInsight = onOpenInsight,
        onCopySetupPrompt = {
            scope.launch {
                clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, setupPrompt)))
                snackbarHostState.showSnackbar(ColoredSnackbarVisuals(copiedMessage, isError = false))
            }
        },
        onCopyDailyPrompt = { viewModel.onEvent(DashboardEvent.RequestDailyPromptCopy) },
        insightDetail = insightDetail,
        insightsCard = insightsCard,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    uiState: DashboardUiState,
    snackbarHostState: SnackbarHostState,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToggleCardManagement: () -> Unit,
    onCancelCardManagement: () -> Unit,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onReorderCards: (List<app.readylytics.health.core.model.domain.dashboard.CardConfiguration>) -> Unit,
    onResetToDefaults: () -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
    navigationCallbacks: DashboardNavigationCallbacks = DashboardNavigationCallbacks(),
    onManageClick: (() -> Unit)? = null,
    onDateSelected: (LocalDate) -> Unit = {},
    earliestDate: LocalDate? = null,
    onDismissInsight: (InsightType) -> Unit = {},
    onRestoreInsights: () -> Unit = {},
    onOpenInsight: (InsightParams) -> Unit = {},
    onCopySetupPrompt: () -> Unit = {},
    onCopyDailyPrompt: () -> Unit = {},
    insightDetail: @Composable (() -> Unit)? = null,
    insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit = { _, _, _, _, _ -> },
) {
    val manageState = rememberManageLayoutState()

    Box(modifier = modifier.fillMaxSize()) {
        CardManagementSheet(
            isOpen = manageState.isManageOpen,
            cardConfigurations = uiState.cardConfigurations,
            onCardVisibilityChanged = onCardVisibilityChanged,
            onCardDisplayModeChanged = onCardDisplayModeChanged,
            onResetToDefaults = onResetToDefaults,
            onDismiss = manageState.closeManage,
            sheetState = manageState.sheetState,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("dashboard_lazy_column"),
            contentPadding = buildLazyColumnPadding(),
        ) {
            dashboardCardContentItems(
                uiState = uiState,
                navigationCallbacks = navigationCallbacks,
                onPreviousDay = onPreviousDay,
                onNextDay = onNextDay,
                earliestDate = earliestDate,
                onDateSelected = onDateSelected,
                onDismissInsight = onDismissInsight,
                onRestoreInsights = onRestoreInsights,
                onOpenInsight = onOpenInsight,
                onCardDisplayModeChanged = onCardDisplayModeChanged,
                onCopySetupPrompt = onCopySetupPrompt,
                onCopyDailyPrompt = onCopyDailyPrompt,
                onCardVisibilityChanged = onCardVisibilityChanged,
                onReorderCards = onReorderCards,
                onToggleCardManagement = onToggleCardManagement,
                insightsCard = insightsCard,
            )
        }

        DashboardSnackbarHost(
            hostState = snackbarHostState,
            isManagingCards = uiState.isManagingCards,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter),
        )

        DashboardEditModeFab(
            isVisible = uiState.isManagingCards,
            onDoneClick = onToggleCardManagement,
            onCancelClick = onCancelCardManagement,
            onManageClick = onManageClick ?: manageState.openManage,
            modifier = Modifier.align(Alignment.BottomEnd),
        )

        insightDetail?.invoke()
    }
}
