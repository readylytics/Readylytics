package app.readylytics.health.feature.dashboard

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.model.InsightType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

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
            .onNodeWithContentDescription("Show explanation for Strong Recovery Signal")
            .performClick()

        composeRule.onNodeWithText("Strong Recovery Signal").assertIsDisplayed()
        composeRule.onNodeWithText("Observed Signal").assertIsDisplayed()
        composeRule.onNodeWithText("What This Might Mean").assertIsDisplayed()
        composeRule.onNodeWithText("What Not To Infer").assertIsDisplayed()
    }

    @Test
    fun infoButtonOpensHrvDataMissingDetails() {
        val uiState =
            createTestUiState(
                activeInsightTypes = setOf(InsightType.RECOVERY_HRV_MISSING),
                currentInsight = InsightType.RECOVERY_HRV_MISSING,
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
                onToggleCardManagement = {},
                onCardVisibilityChanged = { _, _ -> },
                onReorderCards = {},
                onResetToDefaults = {},
            )
        }

        composeRule
            .onNodeWithContentDescription("Show explanation for HRV Data Missing")
            .performClick()

        composeRule.onNodeWithText("What Data Is Missing").assertIsDisplayed()
        composeRule.onNodeWithText("How This Affects Your Score").assertIsDisplayed()
        composeRule.onNodeWithText("What You Can Check").assertIsDisplayed()
    }
}
