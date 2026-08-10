package app.readylytics.health.feature.dashboard

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Resource-backed lookup (rather than a hardcoded literal) for the shared core/ui drag-handle
    // description, matching the pattern used by core/ui's DateSwitcherTest.
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = context.getString(id)

    private fun createTestUiState(
        isManagingCards: Boolean = false,
        selectedDate: LocalDate = LocalDate.now(),
        activeInsightTypes: Set<InsightType> = emptySet(),
        currentInsight: InsightType? = null,
    ): DashboardUiState =
        DashboardUiState(
            summary = null,
            selectedDate = selectedDate,
            isManagingCards = isManagingCards,
            isComputingMetrics = false,
            isCalibrating = false,
            cardConfigurations =
                listOf(
                    CardConfiguration(
                        cardId = CardId.INSIGHTS,
                        isVisible = true,
                        position = 0,
                    ),
                ),
            activeInsightTypes = activeInsightTypes,
            currentInsight = currentInsight,
        )

    @Test
    fun fabIsVisibleWhenEditingEnabled() {
        val uiState = createTestUiState(isManagingCards = true)
        composeRule.setContent {
            DashboardScreen(
                uiState = uiState,
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

        composeRule
            .onNodeWithContentDescription("Done editing")
            .assertIsDisplayed()
    }

    @Test
    fun fabIsHiddenWhenEditingDisabled() {
        val uiState = createTestUiState(isManagingCards = false)
        composeRule.setContent {
            DashboardScreen(
                uiState = uiState,
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

        composeRule
            .onNodeWithContentDescription("Done editing")
            .assertIsNotDisplayed()
    }

    @Test
    fun fabHasProperAccessibilityLabel() {
        val uiState = createTestUiState(isManagingCards = true)
        composeRule.setContent {
            DashboardScreen(
                uiState = uiState,
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

        // Verify the Icon inside FAB has the proper contentDescription
        composeRule
            .onNodeWithContentDescription("Done editing")
            .assertIsDisplayed()
    }

    @Test
    fun infoButtonOpensStrongRecoverySignalDetails() {
        val uiState =
            createTestUiState(
                activeInsightTypes = setOf(InsightType.STRONG_RECOVERY_SIGNAL),
                currentInsight = InsightType.STRONG_RECOVERY_SIGNAL,
            )
        composeRule.setContent {
            var showingDetailFor by remember { mutableStateOf<InsightType?>(null) }
            DashboardScreen(
                uiState = uiState,
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
                insightsCard = { state, _, onDismissInsight, _, _ ->
                    state.currentInsight?.let { insight ->
                        InsightCard(
                            title = "Strong Recovery Signal",
                            body = "",
                            icon = getInsightIcon(insight),
                            onDismiss = { onDismissInsight(insight) },
                            onShowDetails = { showingDetailFor = insight },
                        )
                    }
                },
                insightDetail = {
                    showingDetailFor?.let { Text("detail:${it.name}") }
                },
            )
        }

        composeRule
            .onNodeWithContentDescription("Show explanation for Strong Recovery Signal")
            .performClick()

        composeRule.onNodeWithText("detail:STRONG_RECOVERY_SIGNAL").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // Task 8: display-mode wiring through the real grid + card factory.
    // -------------------------------------------------------------------------

    private fun createConfigurableTestUiState(
        isManagingCards: Boolean,
        hrvRequestedMode: DashboardCardDisplayMode? = null,
    ): DashboardUiState {
        val hrvPresentation =
            UniversalMetricPresentation(
                title = "HRV",
                valueText = "55",
                unitText = "ms",
                secondaryText = null,
                status = MetricStatus.OPTIMAL,
                tooltip = "HRV tooltip text",
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
        val heartRatePresentation =
            UniversalMetricPresentation(
                title = "Heart Rate",
                valueText = "62",
                unitText = "bpm",
                secondaryText = null,
                status = MetricStatus.NEUTRAL,
                tooltip = "",
                accessibilityDescription = "Heart Rate 62 bpm",
                visual = UniversalMetricVisual.ValueOnly,
            )
        val bloodPressurePresentation =
            UniversalMetricPresentation(
                title = "Blood Pressure",
                valueText = "118/76",
                unitText = "mmHg",
                secondaryText = null,
                status = MetricStatus.NEUTRAL,
                tooltip = "",
                accessibilityDescription = "Blood Pressure 118/76 mmHg",
                visual = UniversalMetricVisual.ValueOnly,
            )

        return DashboardUiState(
            summary = null,
            selectedDate = LocalDate.now(),
            isManagingCards = isManagingCards,
            isComputingMetrics = false,
            isCalibrating = false,
            cardDataMap =
                mapOf(
                    CardId.HRV to hrvPresentation,
                    CardId.HEART_RATE to heartRatePresentation,
                    CardId.BLOOD_PRESSURE to bloodPressurePresentation,
                ),
            cardConfigurations =
                listOf(
                    CardConfiguration(cardId = CardId.STEPS, isVisible = true, position = 0),
                    CardConfiguration(
                        cardId = CardId.HRV,
                        isVisible = true,
                        position = 1,
                        requestedDisplayMode = hrvRequestedMode,
                    ),
                    CardConfiguration(cardId = CardId.HEART_RATE, isVisible = true, position = 2),
                    CardConfiguration(cardId = CardId.BLOOD_PRESSURE, isVisible = true, position = 3),
                    CardConfiguration(cardId = CardId.INSIGHTS, isVisible = true, position = 4),
                ),
            stepCount = 4200,
            stepGoal = 10000,
        )
    }

    @Test
    fun displayModeMenu_appearsOnlyOnConfigurableCard_inEditMode() {
        composeRule.setContent {
            DashboardScreen(
                uiState = createConfigurableTestUiState(isManagingCards = true),
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
                onCardDisplayModeChanged = { _, _ -> },
                insightsCard = { _, _, _, _, _ -> Text("insights-content") },
            )
        }

        // Only HRV is catalog-configurable (Gauge/Bar/Value); Heart Rate, Blood Pressure, Steps
        // and Insights must not contribute a selector.
        composeRule
            .onAllNodesWithContentDescription("Change visualization style")
            .assertCountEquals(1)
    }

    @Test
    fun displayModeMenu_selectingHrvBar_invokesCallbackForHrvOnly() {
        var receivedCardId: CardId? = null
        var receivedMode: DashboardCardDisplayMode? = null

        composeRule.setContent {
            DashboardScreen(
                uiState = createConfigurableTestUiState(isManagingCards = true),
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
                onCardDisplayModeChanged = { cardId, mode ->
                    receivedCardId = cardId
                    receivedMode = mode
                },
                insightsCard = { _, _, _, _, _ -> Text("insights-content") },
            )
        }

        composeRule.onNodeWithContentDescription("Change visualization style").performClick()
        composeRule.onNodeWithText("Bar").performClick()

        assertEquals(CardId.HRV, receivedCardId)
        assertEquals(DashboardCardDisplayMode.BAR, receivedMode)
    }

    @Test
    fun stepsAndInsights_remainFixedAndCustom_withNoSelector() {
        composeRule.setContent {
            DashboardScreen(
                uiState = createConfigurableTestUiState(isManagingCards = true),
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
                onCardDisplayModeChanged = { _, _ -> },
                insightsCard = { _, _, _, _, _ -> Text("insights-content") },
            )
        }

        // Steps keeps its fixed full-width Bar (StepsCard); Insights keeps its bespoke content.
        composeRule.onNodeWithText("Daily Steps").assertIsDisplayed()
        composeRule.onNodeWithText("insights-content").assertIsDisplayed()
        // Neither contributes a selector: exactly one exists (HRV's).
        composeRule
            .onAllNodesWithContentDescription("Change visualization style")
            .assertCountEquals(1)
    }

    @Test
    fun normalCardClick_andInfoTooltip_stillWork() {
        var hrvClicked = false
        composeRule.setContent {
            DashboardScreen(
                uiState = createConfigurableTestUiState(isManagingCards = false),
                snackbarHostState = SnackbarHostState(),
                onRefresh = {},
                onPreviousDay = {},
                onNextDay = {},
                onNavigateToSleep = {},
                onNavigateToWorkouts = {},
                onNavigateToRhr = {},
                onNavigateToSteps = {},
                onNavigateToHrv = { hrvClicked = true },
                onToggleCardManagement = {},
                onCardVisibilityChanged = { _, _ -> },
                onReorderCards = {},
                onResetToDefaults = {},
                onCardDisplayModeChanged = { _, _ -> },
                insightsCard = { _, _, _, _, _ -> Text("insights-content") },
            )
        }

        composeRule.onNodeWithContentDescription("HRV 55 ms").performClick()
        assertEquals(true, hrvClicked)

        composeRule
            .onNode(
                hasContentDescription("More information") and
                    hasAnyAncestor(hasContentDescription("HRV 55 ms")),
            ).performClick()
        composeRule.onNodeWithText("HRV tooltip text").assertIsDisplayed()
    }

    @Test
    fun modeChange_doesNotChangeMeasuredCardSize() {
        val state =
            mutableStateOf(
                createConfigurableTestUiState(
                    isManagingCards = true,
                    hrvRequestedMode = DashboardCardDisplayMode.VALUE,
                ),
            )
        composeRule.setContent {
            DashboardScreen(
                uiState = state.value,
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
                onCardDisplayModeChanged = { _, _ -> },
                insightsCard = { _, _, _, _, _ -> Text("insights-content") },
            )
        }

        val initialSize =
            composeRule.onNodeWithContentDescription("HRV 55 ms, Value").fetchSemanticsNode().size

        composeRule.runOnIdle {
            state.value =
                state.value.copy(
                    cardConfigurations =
                        state.value.cardConfigurations.map {
                            if (it.cardId == CardId.HRV) {
                                it.copy(requestedDisplayMode = DashboardCardDisplayMode.BAR)
                            } else {
                                it
                            }
                        },
                )
        }
        composeRule.waitForIdle()

        val updatedSize =
            composeRule.onNodeWithContentDescription("HRV 55 ms, Bar").fetchSemanticsNode().size

        assertEquals(initialSize, updatedSize)
    }

    @Test
    fun infoButtonOpensHrvDataMissingDetails() {
        val uiState =
            createTestUiState(
                activeInsightTypes = setOf(InsightType.RECOVERY_HRV_MISSING),
                currentInsight = InsightType.RECOVERY_HRV_MISSING,
            )
        composeRule.setContent {
            var showingDetailFor by remember { mutableStateOf<InsightType?>(null) }
            DashboardScreen(
                uiState = uiState,
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
                insightsCard = { state, _, onDismissInsight, _, _ ->
                    state.currentInsight?.let { insight ->
                        InsightCard(
                            title = "HRV Data Missing",
                            body = "",
                            icon = getInsightIcon(insight),
                            onDismiss = { onDismissInsight(insight) },
                            onShowDetails = { showingDetailFor = insight },
                        )
                    }
                },
                insightDetail = {
                    showingDetailFor?.let { Text("detail:${it.name}") }
                },
            )
        }

        composeRule
            .onNodeWithContentDescription("Show explanation for HRV Data Missing")
            .performClick()

        composeRule.onNodeWithText("detail:RECOVERY_HRV_MISSING").assertIsDisplayed()
    }

    // -------------------------------------------------------------------------
    // Task 10: drag-handle touch-target regression coverage (ReorderableCardGrid,
    // core:ui). Only in edit mode does the grid render a handle at all.
    // -------------------------------------------------------------------------

    @Test
    fun dragHandleMeetsMinimumTouchTargetSize() {
        composeRule.setContent {
            DashboardScreen(
                uiState = createConfigurableTestUiState(isManagingCards = true),
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
                onCardDisplayModeChanged = { _, _ -> },
                insightsCard = { _, _, _, _, _ -> Text("insights-content") },
            )
        }

        // Compose UI test in this project's Compose BOM does not expose
        // assertTouchWidthIsAtLeast/assertTouchHeightIsAtLeast (only *IsEqualTo variants exist for
        // touch bounds); assertWidthIsAtLeast/assertHeightIsAtLeast are the closest available
        // equivalents and are exact here since the drag handle is a fixed 48.dp Box with no extra
        // touch-target padding (see ReorderableCardGrid.kt), so layout bounds equal touch bounds.
        val dragHandleDescription = string(app.readylytics.health.core.ui.R.string.accessibility_drag_to_reorder)
        composeRule
            .onAllNodesWithContentDescription(dragHandleDescription)
            .onFirst()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    // -------------------------------------------------------------------------
    // AI Recommendation dashboard card: renders and invokes copy callbacks.
    // -------------------------------------------------------------------------

    @Test
    fun aiRecommendationCard_rendersTitleAndBothCopyButtons() {
        val uiState =
            createTestUiState().copy(
                cardConfigurations =
                    listOf(
                        CardConfiguration(
                            cardId = CardId.AI_RECOMMENDATION,
                            isVisible = true,
                            position = 0,
                        ),
                    ),
            )
        composeRule.setContent {
            DashboardScreen(
                uiState = uiState,
                snackbarHostState = SnackbarHostState(),
                onRefresh = {},
                onPreviousDay = {},
                onNextDay = {},
                onNavigateToSleep = {},
                onNavigateToWorkouts = {},
                onNavigateToRhr = {},
                onNavigateToSteps = {},
            )
        }

        composeRule
            .onNodeWithText(string(R.string.ai_recommendation_card_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.ai_recommendation_copy_setup_button))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(string(R.string.ai_recommendation_copy_daily_button))
            .assertIsDisplayed()
    }

    @Test
    fun aiRecommendationCard_copyButtonsInvokeCallbacks() {
        var setupClicks = 0
        var dailyClicks = 0
        val uiState =
            createTestUiState().copy(
                cardConfigurations =
                    listOf(
                        CardConfiguration(
                            cardId = CardId.AI_RECOMMENDATION,
                            isVisible = true,
                            position = 0,
                        ),
                    ),
            )
        composeRule.setContent {
            DashboardScreen(
                uiState = uiState,
                snackbarHostState = SnackbarHostState(),
                onRefresh = {},
                onPreviousDay = {},
                onNextDay = {},
                onNavigateToSleep = {},
                onNavigateToWorkouts = {},
                onNavigateToRhr = {},
                onNavigateToSteps = {},
                onCopySetupPrompt = { setupClicks++ },
                onCopyDailyPrompt = { dailyClicks++ },
            )
        }

        composeRule
            .onNodeWithText(string(R.string.ai_recommendation_copy_setup_button))
            .performClick()
        composeRule
            .onNodeWithText(string(R.string.ai_recommendation_copy_daily_button))
            .performClick()

        assertEquals(1, setupClicks)
        assertEquals(1, dailyClicks)
    }
}
