package app.readylytics.health.ui.components

import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetricTooltipTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun metricTooltip_showsPopupOnIconClick() {
        val testDescription = "Test metric description info"

        composeTestRule.setContent {
            Surface {
                MetricTooltip(
                    description = testDescription,
                )
            }
        }

        // Initially, the tooltip description shouldn't exist in the hierarchy
        composeTestRule.onNodeWithText(testDescription).assertDoesNotExist()

        // Tap on the information icon
        composeTestRule.onNodeWithContentDescription("More information").performClick()

        // The tooltip description should exist and be displayed
        composeTestRule.onNodeWithText(testDescription).assertExists()
        composeTestRule.onNodeWithText(testDescription).assertIsDisplayed()
    }
}
