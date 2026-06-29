package app.readylytics.health.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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
