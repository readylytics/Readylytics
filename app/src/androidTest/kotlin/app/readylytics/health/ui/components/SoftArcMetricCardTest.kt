package app.readylytics.health.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.ui.dashboard.BaselineDeltaDirection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SoftArcMetricCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rendersMetricValueUnitAndBaselineChipWithoutStatusWording() {
        composeRule.setContent {
            MaterialTheme {
                SoftArcMetricCard(
                    title = "RHR",
                    value = "48",
                    unit = "bpm",
                    status = MetricStatus.OPTIMAL,
                    tooltip = "Resting heart rate tooltip body",
                    progress = 0.62f,
                    baselineDeltaText = "↓ 3 bpm vs baseline",
                    baselineDeltaDirection = BaselineDeltaDirection.DOWN,
                )
            }
        }

        composeRule.onNodeWithText("RHR").assertIsDisplayed()
        composeRule.onNodeWithText("48").assertIsDisplayed()
        composeRule.onNodeWithText("bpm").assertIsDisplayed()
        composeRule.onNodeWithText("↓ 3 bpm vs baseline").assertIsDisplayed()
        composeRule.onAllNodesWithText("Optimal").assertCountEquals(0)
        composeRule.onAllNodesWithText("Warning").assertCountEquals(0)
        composeRule.onAllNodesWithText("Poor").assertCountEquals(0)
    }

    @Test
    fun usesExistingTooltipBehavior() {
        composeRule.setContent {
            MaterialTheme {
                SoftArcMetricCard(
                    title = "HRV",
                    value = "62",
                    unit = "ms",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "HRV tooltip body",
                    progress = 0.5f,
                )
            }
        }

        composeRule.onNodeWithContentDescription("More information").performClick()
        composeRule.onNodeWithText("HRV tooltip body").assertIsDisplayed()
    }

    @Test
    fun exposesClickableCardSemanticsWhenClickHandlerProvided() {
        var clicked = false

        composeRule.setContent {
            MaterialTheme {
                SoftArcMetricCard(
                    title = "Recovery",
                    value = "81",
                    unit = "%",
                    status = MetricStatus.OPTIMAL,
                    tooltip = "Recovery tooltip body",
                    progress = 1.3f,
                    baselineDeltaText = "+4 vs baseline",
                    baselineDeltaDirection = BaselineDeltaDirection.UP,
                    onClick = { clicked = true },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Recovery, 81, %, +4 vs baseline")
            .assertHasClickAction()
            .performClick()

        assertTrue(clicked)
    }
}
