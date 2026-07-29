package app.readylytics.health.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun grantPermissions() {
        grantHealthConnectPermissions(BASELINE_PROFILE_PACKAGE_NAME)
        MacrobenchmarkScope(
            packageName = BASELINE_PROFILE_PACKAGE_NAME,
            launchWithClearTask = true,
        ).apply {
            killProcess()
            pressHome()
            startActivityAndWait()
            waitForDashboard()
            killProcess()
        }
    }

    @Test
    fun startup() =
        baselineProfileRule.collect(
            packageName = BASELINE_PROFILE_PACKAGE_NAME,
            includeInStartupProfile = true,
        ) {
            killProcess()
            pressHome()
            startActivityAndWait()
            waitForDashboard()
        }

    @Test
    fun criticalUserJourneys() =
        baselineProfileRule.collect(
            packageName = BASELINE_PROFILE_PACKAGE_NAME,
            includeInStartupProfile = false,
        ) {
            killProcess()
            pressHome()
            startActivityAndWait()
            waitForDashboard()

            navigateToTab(appString(BASELINE_PROFILE_PACKAGE_NAME, "tab_sleep"))
            selectThirtyDayRange()
            revealChart(SLEEP_CHART_TAG)
            waitForNonEmptyChart(SLEEP_CHART_TAG, BASELINE_PROFILE_PACKAGE_NAME)
            panAndZoomChart(SLEEP_CHART_TAG)

            navigateToTab(appString(BASELINE_PROFILE_PACKAGE_NAME, "tab_vitals"))
            selectThirtyDayRange()
            waitForNonEmptyChart(HRV_CHART_TAG, BASELINE_PROFILE_PACKAGE_NAME)
            panAndZoomChart(HRV_CHART_TAG)

            navigateToTab(appString(BASELINE_PROFILE_PACKAGE_NAME, "tab_workouts"))
            selectThirtyDayRange()
            revealChart(ACWR_CHART_TAG)
            waitForNonEmptyChart(ACWR_CHART_TAG, BASELINE_PROFILE_PACKAGE_NAME)
            panAndZoomChart(ACWR_CHART_TAG)

            navigateToTab(appString(BASELINE_PROFILE_PACKAGE_NAME, "tab_settings"))
            waitForSettingsContent(BASELINE_PROFILE_PACKAGE_NAME)
        }
}
