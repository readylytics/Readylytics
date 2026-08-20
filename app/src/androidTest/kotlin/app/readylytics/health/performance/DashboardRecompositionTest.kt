package app.readylytics.health.performance

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.ui.components.CardConfigurationsList
import app.readylytics.health.core.ui.components.CardDataMap
import app.readylytics.health.core.ui.components.ReorderableCardGrid
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.feature.dashboard.DashboardScreen
import app.readylytics.health.feature.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards [DashboardUiState]'s structural-equality contract. Compose's default `mutableStateOf`
 * snapshot policy skips readers when a write is `equals()`-equal to the previous value, so the
 * dashboard root should not recompose when fed a field-for-field identical state (e.g. an
 * upstream flow combiner re-emitting without `distinctUntilChanged`). A regression here means
 * `DashboardUiState.equals()` stopped being structural — a stray non-data field, a dropped
 * `@Immutable`, or an unstable nested type.
 */
@RunWith(AndroidJUnit4::class)
class DashboardRecompositionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode =
        UniversalCardDisplayMode.valueOf(name)

    private fun baseUiState(): DashboardUiState =
        DashboardUiState(
            cardConfigurations =
                listOf(CardConfiguration(cardId = CardId.INSIGHTS, isVisible = true, position = 0)),
        )

    @Test
    fun dashboardScreen_doesNotRecomposeForStructurallyEqualUiState() {
        var recompositionCount = 0
        val uiState = mutableStateOf(baseUiState())

        composeRule.setContent {
            SideEffect { recompositionCount++ }
            DashboardScreen(
                uiState = uiState.value,
                snackbarHostState = SnackbarHostState(),
                onRefresh = {},
                onPreviousDay = {},
                onNextDay = {},
                onNavigateToSleep = {},
                onNavigateToWorkouts = {},
                onNavigateToRhr = {},
                onNavigateToSteps = {},
                onToggleCardManagement = {},
                onCardVisibilityChanged = { _, _ -> },
                onReorderCards = {},
                onResetToDefaults = {},
            )
        }
        composeRule.waitForIdle()
        val countAfterInitialComposition = recompositionCount

        composeRule.runOnIdle { uiState.value = uiState.value.copy() }
        composeRule.waitForIdle()

        assertEquals(countAfterInitialComposition, recompositionCount)
    }

    @Test
    fun dashboardScreen_recomposesWhenUiStateActuallyChanges() {
        var recompositionCount = 0
        val uiState = mutableStateOf(baseUiState())

        composeRule.setContent {
            SideEffect { recompositionCount++ }
            DashboardScreen(
                uiState = uiState.value,
                snackbarHostState = SnackbarHostState(),
                onRefresh = {},
                onPreviousDay = {},
                onNextDay = {},
                onNavigateToSleep = {},
                onNavigateToWorkouts = {},
                onNavigateToRhr = {},
                onNavigateToSteps = {},
                onToggleCardManagement = {},
                onCardVisibilityChanged = { _, _ -> },
                onReorderCards = {},
                onResetToDefaults = {},
            )
        }
        composeRule.waitForIdle()
        val countAfterInitialComposition = recompositionCount

        composeRule.runOnIdle { uiState.value = uiState.value.copy(isManagingCards = true) }
        composeRule.waitForIdle()

        assertEquals(countAfterInitialComposition + 1, recompositionCount)
    }

    /**
     * Task 8: a mode-only change to one card's pending [CardConfiguration] must recompose only
     * that card's body — sibling cards rendered through the same [ReorderableCardGrid] content
     * map must not recompose. Regresses if [CardConfiguration] stops being stable, if the grid's
     * per-slot lookup stops being keyed by cardId, or if a shared `remember` key over-invalidates.
     */
    @Test
    fun reorderableCardGrid_modeChangeOnOneCard_doesNotRecomposeSiblingCardBody() {
        var hrvBodyCompositions = 0
        var readinessBodyCompositions = 0

        val hrvPresentation =
            UniversalMetricPresentation(
                title = "HRV",
                valueText = "55",
                unitText = "ms",
                secondaryText = null,
                status = MetricStatus.OPTIMAL,
                tooltip = "",
                accessibilityDescription = "HRV 55 ms",
                visual =
                    UniversalMetricVisual.Score(
                        rawValue = 55f,
                        minValue = 0f,
                        maxValue = 100f,
                        markerFraction = 0.55f,
                        unavailableReason = null,
                    ),
            )
        val readinessPresentation =
            hrvPresentation.copy(
                title = "Readiness",
                accessibilityDescription = "Readiness 70",
            )

        val cardConfigurations =
            mutableStateOf(
                listOf(
                    CardConfiguration(cardId = CardId.HRV, isVisible = true, position = 0),
                    CardConfiguration(cardId = CardId.READINESS, isVisible = true, position = 1),
                ),
            )

        val cardDataMap =
            CardDataMap(
                mapOf(
                    CardId.HRV to { configuration: CardConfiguration ->
                        SideEffect { hrvBodyCompositions++ }
                        UniversalMetricCard(
                            presentation = hrvPresentation,
                            specification =
                                UniversalMetricCardSpec(
                                    supportedModes =
                                        DashboardCardCatalog.spec(CardId.HRV)!!.supportedModes.map {
                                            it.toUniversalMode()
                                        },
                                ),
                            requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                            isEditing = true,
                            onModeSelected = {},
                        )
                    },
                    CardId.READINESS to { configuration: CardConfiguration ->
                        SideEffect { readinessBodyCompositions++ }
                        UniversalMetricCard(
                            presentation = readinessPresentation,
                            specification =
                                UniversalMetricCardSpec(
                                    supportedModes =
                                        DashboardCardCatalog.spec(CardId.READINESS)!!.supportedModes.map {
                                            it.toUniversalMode()
                                        },
                                ),
                            requestedMode = DashboardCardCatalog.requestedMode(configuration).toUniversalMode(),
                            isEditing = true,
                            onModeSelected = {},
                        )
                    },
                ),
            )

        composeRule.setContent {
            var configs by cardConfigurations
            ReorderableCardGrid(
                cardConfigurations = CardConfigurationsList(configs),
                cardDataMap = cardDataMap,
                isEditing = true,
                onCardRemove = {},
                onCardReorder = { configs = it },
            )
        }
        composeRule.waitForIdle()

        val hrvCountAfterInitialComposition = hrvBodyCompositions
        val readinessCountAfterInitialComposition = readinessBodyCompositions

        composeRule.runOnIdle {
            cardConfigurations.value =
                cardConfigurations.value.map {
                    if (it.cardId == CardId.HRV) {
                        it.copy(requestedDisplayMode = DashboardCardDisplayMode.BAR)
                    } else {
                        it
                    }
                }
        }
        composeRule.waitForIdle()

        assertTrue(hrvBodyCompositions > hrvCountAfterInitialComposition)
        assertEquals(readinessCountAfterInitialComposition, readinessBodyCompositions)
    }
}
