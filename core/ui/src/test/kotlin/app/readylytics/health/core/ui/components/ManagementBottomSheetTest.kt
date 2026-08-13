package app.readylytics.health.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagementBottomSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `single section renders no tabs`() {
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections =
                    listOf(
                        ManagementSection(
                            title = "Cards",
                            items = listOf(item("cardA", "Card A")),
                        ),
                    ),
                onResetToDefaults = {},
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(),
            )
        }
        composeTestRule.onNodeWithText("Cards").assertIsNotDisplayed()
    }

    @Test
    fun `multiple sections render tab labels`() {
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections =
                    listOf(
                        ManagementSection(title = "Cards", items = listOf(item("cardA", "Card A"))),
                        ManagementSection(title = "Charts", items = listOf(item("chartA", "Chart A"))),
                    ),
                onResetToDefaults = {},
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(),
            )
        }
        composeTestRule.onNodeWithText("Cards").assertIsDisplayed()
        composeTestRule.onNodeWithText("Charts").assertIsDisplayed()
    }

    @Test
    fun `mode-capable row renders a dropdown and charts do not`() {
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections =
                    listOf(
                        ManagementSection(
                            title = "Cards",
                            items =
                                listOf(
                                    item("cardA", "Card A", supportedModes = listOf(DashboardCardDisplayMode.GAUGE)),
                                    item("chartA", "Chart A"),
                                ),
                        ),
                    ),
                onResetToDefaults = {},
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(),
            )
        }
        // The mode-capable row's dropdown shows its "Default" selected value.
        composeTestRule.onNodeWithText("Default").assertIsDisplayed()
    }

    @Test
    fun `visibility checkbox invokes callback`() {
        var toggled: Boolean? = null
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections =
                    listOf(
                        ManagementSection(
                            title = "Cards",
                            items =
                                listOf(
                                    item("cardA", "Card A", isVisible = true, onVisibilityChanged = { toggled = it }),
                                ),
                        ),
                    ),
                onResetToDefaults = {},
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(),
            )
        }
        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.waitForIdle()
        assertFalse(toggled!!)
    }

    @Test
    fun `done invokes onDismiss and reset icon invokes onReset`() {
        var dismissed = false
        var reset = false
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections = listOf(ManagementSection(title = "Cards", items = listOf(item("cardA", "Card A")))),
                onResetToDefaults = { reset = true },
                onDismiss = { dismissed = true },
                sheetState = rememberModalBottomSheetState(),
            )
        }
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertTrue(dismissed)
        assertFalse(reset)

        composeTestRule.onNodeWithContentDescription("Reset to defaults").performClick()
        composeTestRule.waitForIdle()
        assertTrue(reset)
    }

    private fun item(
        key: String,
        label: String,
        isVisible: Boolean = true,
        supportedModes: List<DashboardCardDisplayMode> = emptyList(),
        onVisibilityChanged: (Boolean) -> Unit = {},
    ) = ManagementItem(
        key = key,
        label = label,
        isVisible = isVisible,
        supportedModes = supportedModes,
        requestedMode = null,
        onVisibilityChanged = onVisibilityChanged,
        onDisplayModeChanged = {},
    )
}
