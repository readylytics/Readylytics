package app.readylytics.health.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Pause the test clock so MainScaffold's indefinite pull-to-refresh indicator
    // (driven by isSyncing on launch) does not keep the Compose clock busy and
    // block waitForIdle() forever. Finders/interactions still operate normally.
    @Before
    fun pauseClock() {
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun verifyTabSwitching() {
        // Dashboard should be visible by default
        composeRule
            .onAllNodesWithContentDescription("Dashboard", substring = true)
            .onFirst()
            .assertIsDisplayed()

        // Switch to Sleep tab
        composeRule
            .onAllNodesWithContentDescription("Sleep", substring = true)
            .onFirst()
            .performClick()

        // Switch to Vitals tab
        composeRule
            .onAllNodesWithContentDescription("Vitals", substring = true)
            .onFirst()
            .performClick()

        // Switch to Workouts tab
        composeRule
            .onAllNodesWithContentDescription("Workouts", substring = true)
            .onFirst()
            .performClick()

        // Switch to Settings tab
        composeRule
            .onAllNodesWithContentDescription("Settings", substring = true)
            .onFirst()
            .performClick()
    }
}
