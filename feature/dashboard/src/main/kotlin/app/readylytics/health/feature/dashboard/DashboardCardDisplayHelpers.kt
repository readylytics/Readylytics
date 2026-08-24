package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.model.InsightType
import app.readylytics.health.core.scoring.domain.insights.InsightParams
import app.readylytics.health.core.ui.components.EditModeFab
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun DashboardSnackbarHost(
    hostState: SnackbarHostState,
    isManagingCards: Boolean,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier =
            modifier
                .then(buildSnackbarPaddingModifier(isManagingCards)),
        snackbar = { data ->
            val isError = (data.visuals as? ColoredSnackbarVisuals)?.isError == true
            Snackbar(
                data,
                containerColor =
                    if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.inverseSurface
                    },
                contentColor =
                    if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.inverseOnSurface
                    },
            )
        },
    )
}

@Composable
fun DashboardEditModeFab(
    isVisible: Boolean,
    onDoneClick: () -> Unit,
    onCancelClick: () -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EditModeFab(
        isVisible = isVisible,
        onDoneClick = onDoneClick,
        onCancelClick = onCancelClick,
        onManageClick = onManageClick,
        modifier = modifier.then(buildEditModeFabModifier()),
    )
}

fun LazyListScope.dashboardCardContentItems(
    uiState: DashboardUiState,
    navigationCallbacks: DashboardNavigationCallbacks,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    earliestDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    onDismissInsight: (InsightType) -> Unit,
    onRestoreInsights: () -> Unit,
    onOpenInsight: (InsightParams) -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit,
    onCopySetupPrompt: () -> Unit,
    onCopyDailyPrompt: () -> Unit,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onReorderCards: (List<app.readylytics.health.core.model.domain.dashboard.CardConfiguration>) -> Unit,
    onToggleCardManagement: () -> Unit,
    insightsCard: @Composable (
        DashboardUiState,
        Boolean,
        (InsightType) -> Unit,
        () -> Unit,
        (InsightParams) -> Unit,
    ) -> Unit,
) {
    val today = uiState.today
    val summary = uiState.summary

    item(key = "date_switcher") {
        DateSwitcherSection(
            selectedDate = uiState.selectedDate,
            onPreviousDay = onPreviousDay,
            onNextDay = onNextDay,
            today = today,
            onDateSelected = onDateSelected,
            earliestDate = earliestDate,
        )
    }

    item(key = "date_switcher_spacer") {
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap))
    }

    if (summary == null && !uiState.isComputingMetrics && (uiState.selectedDate < today)) {
        item(key = "no_data_placeholder") {
            NoDataPlaceholder()
        }
    } else {
        item(key = "metric_grid") {
            // Memoize the card data map so it is only rebuilt when a field the cards
            // actually render changes. Keying on the single DashboardCardInputs holder
            // (instead of a multi-key vararg) avoids an Any?[] allocation per
            // recomposition while still excluding the high-frequency sync fields
            // (isRefreshing/recalcProgress) that previously forced ReorderableCardGrid
            // and every child card to recompose each frame during a resync.
            MetricGridSection(
                uiState = uiState,
                navigationCallbacks = navigationCallbacks,
                isEditing = uiState.isManagingCards,
                isLoading = uiState.isComputingMetrics,
                onDismissInsight = onDismissInsight,
                onRestoreInsights = onRestoreInsights,
                onOpenInsight = onOpenInsight,
                onCardDisplayModeChanged = onCardDisplayModeChanged,
                onCopySetupPrompt = onCopySetupPrompt,
                onCopyDailyPrompt = onCopyDailyPrompt,
                onCardVisibilityChanged = onCardVisibilityChanged,
                onReorderCards = onReorderCards,
                insightsCard = insightsCard,
            )
        }
    }

    item(key = "spacer_bottom") { Spacer(modifier = Modifier.height(MaterialTheme.spacing.pageSectionGap)) }

    item(key = "status_legend") {
        StatusLegendSection()
    }

    if (!uiState.isManagingCards) {
        item(key = "customize_button") {
            CustomizeButton(onToggleCardManagement)
        }
    }
}

@Composable
internal fun DateSwitcherSection(
    selectedDate: LocalDate,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    today: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    earliestDate: LocalDate?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    ) {
        DateSwitcher(
            selectedDate = selectedDate,
            onPreviousDay = onPreviousDay,
            onNextDay = onNextDay,
            today = today,
            onDateSelected = onDateSelected,
            earliestDate = earliestDate,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun StatusLegendSection() {
    StatusLegend()
}

@Composable
internal fun CustomizeButton(onToggleCardManagement: () -> Unit) {
    FilledTonalButton(
        onClick = onToggleCardManagement,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGap,
                ),
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Text(
            text = stringResource(CoreUiR.string.action_customize),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
internal fun buildSnackbarPaddingModifier(isManagingCards: Boolean): Modifier =
    Modifier.padding(
        start = MaterialTheme.spacing.pageHorizontal,
        end = MaterialTheme.spacing.pageHorizontal,
        top = MaterialTheme.spacing.pageSectionGap,
        // 88.dp: no grid token, clears the edit-mode FAB height exactly
        bottom = if (isManagingCards) 88.dp else MaterialTheme.spacing.pageBottom,
    )

@Composable
internal fun NoDataPlaceholder() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.doubleExtraLarge),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(app.readylytics.health.core.ui.R.string.dashboard_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun buildEditModeFabModifier(): Modifier = Modifier.padding(MaterialTheme.spacing.pageHorizontal)

@Composable
internal fun buildLazyColumnPadding(): PaddingValues =
    PaddingValues(
        top = MaterialTheme.spacing.pageTop,
        bottom = MaterialTheme.spacing.pageBottom,
    )
