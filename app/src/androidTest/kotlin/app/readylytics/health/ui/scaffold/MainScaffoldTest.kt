package app.readylytics.health.ui.scaffold

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainScaffoldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigationBarItemsExist() {
        // Check if at least one tab exists (Dashboard is usually the first)
        composeRule
            .onAllNodesWithContentDescription("Dashboard", substring = true)
            .onFirst()
            .assertIsEnabled()
    }

    @Test
    fun compactWidth_rendersBottomNavigation() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 800.dp))
            ) {
                MainScaffold()
            }
        }

        // Verify Dashboard item exists and is displayed
        val dashboardNode = composeRule
            .onAllNodesWithContentDescription("Dashboard", substring = true)
            .onFirst()
        
        dashboardNode.assertIsDisplayed()

        // In compact width (bottom navigation), the item should be at the bottom of the screen.
        // Screen height is 800.dp. Bottom bar height is around 80.dp.
        // So the item should have Y coordinate > 700.dp.
        val bounds = dashboardNode.getUnclippedBoundsInRoot()
        assert(bounds.top > 700.dp) {
            "Dashboard item should be at the bottom of the screen in compact layout, but top was ${bounds.top}"
        }
    }

    @Test
    fun mediumWidth_rendersNavigationRail() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(700.dp, 800.dp))
            ) {
                MainScaffold()
            }
        }

        val dashboardNode = composeRule
            .onAllNodesWithContentDescription("Dashboard", substring = true)
            .onFirst()
        
        dashboardNode.assertIsDisplayed()

        // In medium width (navigation rail), the item should be on the left/top of the screen.
        // Rail items are usually positioned near the top of the screen.
        // So the Y coordinate should be < 200.dp.
        val bounds = dashboardNode.getUnclippedBoundsInRoot()
        assert(bounds.top < 200.dp) {
            "Dashboard item should be at the top/left of the screen in medium layout, but top was ${bounds.top}"
        }
    }

    @Test
    fun expandedWidth_rendersNavigationRailOrDrawer() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(1000.dp, 800.dp))
            ) {
                MainScaffold()
            }
        }

        val dashboardNode = composeRule
            .onAllNodesWithContentDescription("Dashboard", substring = true)
            .onFirst()
        
        dashboardNode.assertIsDisplayed()

        // In expanded width, it renders rail/drawer, so the item should be on the left/top of the screen.
        // So the Y coordinate should be < 200.dp.
        val bounds = dashboardNode.getUnclippedBoundsInRoot()
        assert(bounds.top < 200.dp) {
            "Dashboard item should be at the top/left of the screen in expanded layout, but top was ${bounds.top}"
        }
    }
}
