package app.readylytics.health.ui.scaffold

import androidx.activity.compose.setContent
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MainScaffoldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun launchActivity() {
        renderScaffold()
    }

    @Test
    fun navigationBarItemsExist() {
        val dashboardNode = composeRule.onNodeWithText("Dashboard")
        waitUntilDisplayed(dashboardNode)
        dashboardNode.assertIsEnabled()
    }

    @Test
    fun compactWidth_rendersBottomNavigation() {
        renderScaffold(width = 400.dp, height = 800.dp)

        val dashboardNode = composeRule.onNodeWithText("Dashboard")
        waitUntilDisplayed(dashboardNode)

        val bounds = dashboardNode.getUnclippedBoundsInRoot()
        assert(bounds.top > 700.dp) {
            "Dashboard item should be at the bottom of the screen in compact layout, but top was ${bounds.top}"
        }
    }

    @Test
    fun mediumWidth_rendersNavigationRail() {
        renderScaffold(width = 700.dp, height = 800.dp)

        val dashboardNode = composeRule.onNodeWithText("Dashboard")
        waitUntilDisplayed(dashboardNode)

        val bounds = dashboardNode.getUnclippedBoundsInRoot()
        assert(bounds.top < 200.dp) {
            "Dashboard item should be at the top/left of the screen in medium layout, but top was ${bounds.top}"
        }
    }

    @Test
    fun expandedWidth_rendersNavigationRailOrDrawer() {
        renderScaffold(width = 1000.dp, height = 800.dp)

        val dashboardNode = composeRule.onNodeWithText("Dashboard")
        waitUntilDisplayed(dashboardNode)

        val bounds = dashboardNode.getUnclippedBoundsInRoot()
        assert(bounds.top < 200.dp) {
            "Dashboard item should be at the top/left of the screen in expanded layout, but top was ${bounds.top}"
        }
    }

    private fun renderScaffold(
        width: Dp? = null,
        height: Dp? = null,
    ) {
        composeRule.activity.setContent {
            if (width != null && height != null) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(width, height)),
                ) {
                    MainScaffold()
                }
            } else {
                MainScaffold()
            }
        }
        composeRule.waitForIdle()
    }

    private fun waitUntilDisplayed(
        node: SemanticsNodeInteraction,
        timeoutMillis: Long = 10_000,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching { node.assertIsDisplayed() }.isSuccess
        }
    }
}
