package app.readylytics.health.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun launchActivity() {
        composeRule.mainClock.autoAdvance = false
        scenario = ActivityScenario.launch(MainActivity::class.java)
        advanceFrame()
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
        advanceFrame()
        tab.assertIsSelected()
    }

    private fun advanceFrame() {
        composeRule.mainClock.advanceTimeByFrame()
    }
}
