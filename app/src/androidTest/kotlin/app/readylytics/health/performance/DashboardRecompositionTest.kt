package app.readylytics.health.performance

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.feature.dashboard.DashboardScreen
import app.readylytics.health.feature.dashboard.DashboardUiState
import org.junit.Assert.assertEquals
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
}
