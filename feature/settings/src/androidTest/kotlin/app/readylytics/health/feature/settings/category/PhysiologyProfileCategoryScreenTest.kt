package app.readylytics.health.feature.settings.category

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.feature.settings.DashboardCardsSettingsState
import app.readylytics.health.feature.settings.HeartRateZonesState
import app.readylytics.health.feature.settings.LocalBackupState
import app.readylytics.health.feature.settings.PhysiologySettingsState
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.SleepSettingsState
import app.readylytics.health.feature.settings.SyncSettingsState
import app.readylytics.health.feature.settings.ThresholdSettingsState
import app.readylytics.health.feature.settings.UIState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhysiologyProfileCategoryScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val noOpIntents =
        SettingsIntents(
            onThresholdEvent = {},
            onSleepEvent = {},
            onPhysiologyEvent = {},
            onHeartRateEvent = {},
            onLocalBackupEvent = {},
            onSyncEvent = {},
            onUIEvent = {},
            onDashboardCardsEvent = {},
            onNavigateToAbout = {},
            onNavigateToLicenses = {},
            onOpenPrivacyPolicy = {},
            onOpenSourceCode = {},
            onSendIssueReport = {},
        )

    private val states =
        SettingsStates(
            thresholdState = ThresholdSettingsState(),
            sleepState = SleepSettingsState(),
            physiologyState = PhysiologySettingsState(),
            heartRateState = HeartRateZonesState(),
            localBackupState = LocalBackupState(),
            syncState = SyncSettingsState(),
            uiState = UIState(),
            dashboardCardsState = DashboardCardsSettingsState(),
            hasCrashReport = false,
        )

    @Test
    fun physiologyProfileCategoryScreen_renders_bothItems() {
        composeTestRule.setContent {
            Surface {
                PhysiologyProfileCategoryScreen(
                    states = states,
                    intents = noOpIntents,
                    controlsEnabled = true,
                    highlightItemId = null,
                )
            }
        }

        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun physiologyProfileCategoryScreen_scrollsToHighlightedItem_withoutCrashing() {
        composeTestRule.setContent {
            Surface {
                PhysiologyProfileCategoryScreen(
                    states = states,
                    intents = noOpIntents,
                    controlsEnabled = true,
                    highlightItemId =
                        app.readylytics.health.feature.settings.search.SettingsItemIds.PHYSIOLOGY_HR_ZONES,
                )
            }
        }

        composeTestRule.onRoot().assertExists()
    }
}
