package app.readylytics.health.ui.navigation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.readylytics.health.MainActivity
import app.readylytics.health.core.ui.R as CoreUiR
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootNavigationTest {
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

    private val continueInBackgroundLabel: String by lazy {
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(CoreUiR.string.sync_progress_continue_in_background)
    }

    @Before
    fun launchActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Health Connect SDK not available — test skipped",
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE,
        )
        val granted =
            runBlocking {
                HealthConnectClient
                    .getOrCreate(context)
                    .permissionController
                    .getGrantedPermissions()
            }
        val requiredPermissions =
            setOf(
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class),
                "android.permission.health.READ_HEALTH_DATA_HISTORY",
            )
        assumeTrue(
            "Health Connect read permissions not granted — test skipped",
            granted.containsAll(requiredPermissions),
        )
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
        val dashboardTab = composeRule.onNode(isTabWithText("Dashboard"))
        waitUntilDisplayed(dashboardTab)
        dashboardTab.assertIsSelected()

        listOf("Sleep", "Vitals", "Workouts", "Settings").forEach(::selectTab)
    }

    // Re-clicks inside the wait loop: a single click issued while the freshly launched activity is
    // still settling can be dropped, and waiting alone never recovers from a lost click. Clicking an
    // already-selected tab is a no-op, so retrying is safe.
    //
    // A fresh install starts with scoringVersion 0, so startup enqueues the recompute-only historical
    // resync. While it runs, opening Settings auto-redirects to the full-screen SyncProgress
    // destination, which hides the navigation bar; dismiss it via "Continue in background" (the
    // user-facing escape hatch, which also suppresses re-redirects for this run) and retry.
    private fun selectTab(label: String) {
        val tab = composeRule.onNode(isTabWithText(label))
        val continueInBackground = composeRule.onNode(hasText(continueInBackgroundLabel))
        composeRule.waitUntil(timeoutMillis = TAB_SELECTION_TIMEOUT_MILLIS) {
            if (runCatching { tab.assertIsSelected() }.isSuccess) {
                true
            } else {
                runCatching { continueInBackground.performClick() }
                runCatching { tab.performClick() }
                false
            }
        }
        tab.assertIsSelected()
    }

    private fun isTabWithText(label: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(label)

    private fun waitUntilDisplayed(
        node: SemanticsNodeInteraction,
        timeoutMillis: Long = 10_000,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            runCatching { node.assertIsDisplayed() }.isSuccess
        }
    }

    private companion object {
        const val TAB_SELECTION_TIMEOUT_MILLIS = 10_000L
    }
}
