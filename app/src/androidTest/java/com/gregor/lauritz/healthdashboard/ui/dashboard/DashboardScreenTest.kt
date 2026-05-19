package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
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
                        cardId = CardId.STEPS,
                        isVisible = true,
                        position = 0,
                    ),
                ),
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
}
