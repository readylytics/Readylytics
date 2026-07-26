package app.readylytics.health.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "app.readylytics.health.macrobenchmark"
private const val WAIT_TIMEOUT_MS = 15_000L
private const val JOURNEY_ITERATIONS = 10

// Magic string must match the testTag added to VitalsScreen.kt's HRV TrendChart
// (feature/vitals module) -- :benchmark is a black-box com.android.test module
// with no compile dependency on :app or its feature modules, so this cannot be
// a shared constant.
private const val HRV_CHART_TAG = "HrvTrendChart"
private const val THIRTY_DAY_RANGE_LABEL = "30D"

// Magic string must match R.string.message_no_data_available (core/ui module).
// TrendChart's EmptyChartPlaceholder branch forwards the SAME modifier (and
// therefore the SAME testTag) as the real chart content, so a hasObject(By.res(...))
// wait alone cannot tell a rendered chart apart from the "no data" placeholder --
// e.g. if BenchmarkDataSeeder failed to seed. Waiting for this text's absence
// closes that gap.
private const val NO_DATA_TEXT = "No data available"

// By.text(NO_DATA_TEXT) alone matches ANYWHERE in the accessibility tree, not just
// the HRV chart -- e.g. OxygenSaturationRecord is an optional HC permission, so on a
// device with real synced data (no optional SpO2 grant) the SpO2 chart can legitimately
// show its own "No data available" placeholder while the HRV chart is fine. Scoping the
// selector to a node tagged HRV_CHART_TAG that itself has NO_DATA_TEXT as a descendant
// keeps the wait specific to the HRV chart's own subtree.
private fun hrvChartShowingNoData() = By.res(HRV_CHART_TAG).hasDescendant(By.text(NO_DATA_TEXT))

// AGP's connectedBenchmarkAndroidTest reinstalls the target app fresh on every
// invocation, which wipes any Health Connect permission grant made between runs --
// confirmed by observing a new install-path hash logged on each run. Granting these
// at @Before time (the same androidx.test.rules.GrantPermissionRule mechanism, called
// directly here since the permission set is fixed and known statically) makes the
// suite self-sufficient regardless of prior device state.
private val REQUIRED_HEALTH_CONNECT_PERMISSIONS =
    listOf(
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_HEART_RATE_VARIABILITY",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_HEALTH_DATA_HISTORY",
    )

@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Before
    fun grantHealthConnectPermissions() {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        REQUIRED_HEALTH_CONNECT_PERMISSIONS.forEach { permission ->
            uiAutomation.grantRuntimePermission(PACKAGE_NAME, permission)
        }
    }

    @Test
    fun vitalsFling() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = JOURNEY_ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                navigateToVitals()
            },
            measureBlock = {
                val scrollable =
                    device.findObject(By.scrollable(true))
                        ?: error("Vitals scroll container not found")
                repeat(2) {
                    scrollable.fling(Direction.DOWN)
                    device.waitForIdle()
                    scrollable.fling(Direction.UP)
                    device.waitForIdle()
                }
            },
        )

    @Test
    fun vitalsChartPanAndZoom() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = JOURNEY_ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                navigateToVitals()
                device
                    .findObject(By.text(THIRTY_DAY_RANGE_LABEL))
                    ?.click()
                    ?: error("30D range selector not found")
                check(device.wait(Until.hasObject(By.res(HRV_CHART_TAG)), WAIT_TIMEOUT_MS)) {
                    "HRV chart not found after selecting $THIRTY_DAY_RANGE_LABEL range"
                }
                check(device.wait(Until.gone(hrvChartShowingNoData()), WAIT_TIMEOUT_MS)) {
                    "HRV chart still showing empty-state placeholder after selecting " +
                        "$THIRTY_DAY_RANGE_LABEL range (seeding may have failed)"
                }
            },
            measureBlock = {
                val chart =
                    device.findObject(By.res(HRV_CHART_TAG))
                        ?: error("HRV chart not found")
                val bounds = chart.visibleBounds
                val centerY = bounds.centerY()
                device.swipe(bounds.right - 50, centerY, bounds.left + 50, centerY, 20)
                device.waitForIdle()
                device.swipe(bounds.left + 50, centerY, bounds.right - 50, centerY, 20)
                device.waitForIdle()
                chart.pinchOpen(0.8f, 200)
                device.waitForIdle()
                chart.pinchClose(0.8f, 200)
                device.waitForIdle()
            },
        )

    @Test
    fun dashboardVitalsTabSwitch() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = JOURNEY_ITERATIONS,
            setupBlock = { startActivityAndWait() },
            measureBlock = {
                repeat(3) {
                    device.findObject(By.text("Vitals"))?.click()
                        ?: error("Vitals nav item not found")
                    check(device.wait(Until.hasObject(By.res(HRV_CHART_TAG)), WAIT_TIMEOUT_MS)) {
                        "HRV chart not found after switching to Vitals tab"
                    }
                    check(device.wait(Until.gone(hrvChartShowingNoData()), WAIT_TIMEOUT_MS)) {
                        "HRV chart still showing empty-state placeholder after switching to " +
                            "Vitals tab (seeding may have failed)"
                    }
                    device.findObject(By.text("Dashboard"))?.click()
                        ?: error("Dashboard nav item not found")
                    device.waitForIdle()
                }
            },
        )
}

private fun MacrobenchmarkScope.navigateToVitals() {
    check(device.wait(Until.hasObject(By.text("Vitals")), WAIT_TIMEOUT_MS)) {
        "Vitals nav item not found"
    }
    device.findObject(By.text("Vitals"))?.click() ?: error("Vitals nav item not found")
    check(device.wait(Until.hasObject(By.res(HRV_CHART_TAG)), WAIT_TIMEOUT_MS)) {
        "HRV chart not found after navigating to Vitals"
    }
    check(device.wait(Until.gone(hrvChartShowingNoData()), WAIT_TIMEOUT_MS)) {
        "HRV chart still showing empty-state placeholder after navigating to Vitals " +
            "(seeding may have failed)"
    }
}
