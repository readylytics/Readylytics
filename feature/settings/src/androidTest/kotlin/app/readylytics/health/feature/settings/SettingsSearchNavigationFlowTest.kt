package app.readylytics.health.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSearchNavigationFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun searchingForRasScaling_navigatesToTrainingScreen_andShowsTheControl() {
        composeTestRule.setContent {
            Surface {
                SettingsScreen(
                    thresholdState = ThresholdSettingsState(),
                    sleepState = SleepSettingsState(),
                    physiologyState = PhysiologySettingsState(),
                    heartRateState = HeartRateZonesState(),
                    localBackupState = LocalBackupState(),
                    syncState = SyncSettingsState(),
                    uiState = UIState(),
                    dashboardCardsState = DashboardCardsSettingsState(),
                    onThresholdEvent = {},
                    onSleepEvent = {},
                    onPhysiologyEvent = {},
                    onHeartRateEvent = {},
                    onLocalBackupEvent = {},
                    onSyncEvent = {},
                    onUIEvent = {},
                    onDashboardCardsEvent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Search settings…", substring = true).performTextInput("ras scaling")
        composeTestRule.onNodeWithText("RAS Scaling Factor", substring = true).performClick()

        composeTestRule.onNodeWithText("Search settings…", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("RAS Scaling Factor", substring = true).assertIsDisplayed()
    }
}
