package app.readylytics.health.feature.workouts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResidualFatigueSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun residualFatigueSection_rendersHeaderAndRangeButtons() {
        composeRule.setContent {
            FitDashboardTheme {
                ResidualFatigueSection(
                    uiState = WorkoutsUiState(),
                    onRangeSelected = {},
                    parentScrollInProgress = { false },
                )
            }
        }

        composeRule.onNodeWithText("Residual Fatigue").assertIsDisplayed()
        composeRule.onNodeWithText("1D").assertIsDisplayed()
        composeRule.onNodeWithText("3D").assertIsDisplayed()
        composeRule.onNodeWithText("7D").assertIsDisplayed()
    }

    @Test
    fun residualFatigueSection_clickSegmentedButtons_invokesCallback() {
        var selectedRange: FatigueCurveRange? = null
        composeRule.setContent {
            FitDashboardTheme {
                ResidualFatigueSection(
                    uiState = WorkoutsUiState(selectedFatigueRange = FatigueCurveRange.ONE_DAY),
                    onRangeSelected = { selectedRange = it },
                    parentScrollInProgress = { false },
                )
            }
        }

        composeRule.onNodeWithText("3D").performClick()
        assertEquals(FatigueCurveRange.THREE_DAYS, selectedRange)

        composeRule.onNodeWithText("7D").performClick()
        assertEquals(FatigueCurveRange.SEVEN_DAYS, selectedRange)

        composeRule.onNodeWithText("1D").performClick()
        assertEquals(FatigueCurveRange.ONE_DAY, selectedRange)
    }
}
