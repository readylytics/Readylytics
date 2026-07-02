package app.readylytics.health.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.readylytics.health.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationTest {
    // Grant the Health Connect read permissions the app requires so the launch routes
    // to the main tab shell instead of the onboarding permission flow. Without these,
    // a fresh emulator reports them missing and MainActivity never shows the tab bar.
    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_HEART_RATE_VARIABILITY",
            "android.permission.health.READ_EXERCISE",
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_HEALTH_DATA_HISTORY",
        )

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun closeActivity() {
        if (::scenario.isInitialized) {
            scenario.close()
        }
    }

    @Test
    fun verifyTabSwitching() {
        // Dashboard should be visible by default
        composeRule
            .onAllNodesWithContentDescription("Dashboard", substring = true)
            .onFirst()
            .assertIsDisplayed()
            .assertIsSelected()

        listOf("Sleep", "Vitals", "Workouts", "Settings").forEach(::selectTab)
    }

    private fun selectTab(contentDescription: String) {
        val tab =
            composeRule
                .onAllNodesWithContentDescription(contentDescription, substring = true)
                .onFirst()

        tab.performClick()
        tab.assertIsSelected()
    }
}
