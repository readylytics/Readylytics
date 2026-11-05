package app.readylytics.health.feature.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.domain.validation.BirthdayBounds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class SettingsSearchNavigationFlowTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val testBirthdayBounds =
        BirthdayBounds.at(Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC))

    @Test
    fun searchingForRasScaling_navigatesToTrainingScreen_andShowsTheControl() {
        composeTestRule.setContent {
            Surface {
                SettingsScreen(
                    thresholdState = ThresholdSettingsState(),
                    sleepState = SleepSettingsState(),
                    physiologyState = PhysiologySettingsState(birthdayBounds = testBirthdayBounds),
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

    @Test
    fun navigatingToCategory_andClickingBackButton_returnsToHomeScreen() {
        composeTestRule.setContent {
            Surface {
                SettingsScreen(
                    thresholdState = ThresholdSettingsState(),
                    sleepState = SleepSettingsState(),
                    physiologyState = PhysiologySettingsState(birthdayBounds = testBirthdayBounds),
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

        composeTestRule.onNodeWithText("Physiology Profile").performClick()
        composeTestRule.onNodeWithText("Search settings…", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Activity Profile", substring = true).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Back").performClick()
        composeTestRule.onNodeWithText("Search settings…", substring = true).assertIsDisplayed()
    }
}
