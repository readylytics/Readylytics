package app.readylytics.health.core.ui.components

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayModeDropdownSelectorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `null selected mode renders Default label`() {
        composeTestRule.setContent {
            DisplayModeDropdownSelector(
                selectedMode = null,
                supportedModes =
                    listOf(
                        DashboardCardDisplayMode.GAUGE,
                        DashboardCardDisplayMode.VALUE,
                    ),
                onModeSelected = {},
            )
        }
        composeTestRule.onNodeWithText("Default").assertExists()
    }

    @Test
    fun `selected mode renders its label`() {
        composeTestRule.setContent {
            DisplayModeDropdownSelector(
                selectedMode = DashboardCardDisplayMode.GAUGE,
                supportedModes =
                    listOf(
                        DashboardCardDisplayMode.GAUGE,
                        DashboardCardDisplayMode.VALUE,
                    ),
                onModeSelected = {},
            )
        }
        composeTestRule.onNodeWithText("Gauge").assertExists()
    }
}
