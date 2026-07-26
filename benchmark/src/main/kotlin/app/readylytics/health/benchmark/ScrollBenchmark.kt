package app.readylytics.health.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "app.readylytics.health"
private const val WAIT_TIMEOUT_MS = 15_000L
private const val JOURNEY_ITERATIONS = 10

// Magic string must match the testTag added to VitalsScreen.kt's HRV TrendChart
// (feature/vitals module) -- :benchmark is a black-box com.android.test module
// with no compile dependency on :app or its feature modules, so this cannot be
// a shared constant.
private const val HRV_CHART_TAG = "HrvTrendChart"
private const val THIRTY_DAY_RANGE_LABEL = "30D"

@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun vitalsFling() =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = JOURNEY_ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                navigateToVitals(device)
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
                navigateToVitals(device)
                device
                    .findObject(By.text(THIRTY_DAY_RANGE_LABEL))
                    ?.click()
                    ?: error("30D range selector not found")
                device.wait(Until.hasObject(By.res(PACKAGE_NAME, HRV_CHART_TAG)), WAIT_TIMEOUT_MS)
            },
            measureBlock = {
                val chart =
                    device.findObject(By.res(PACKAGE_NAME, HRV_CHART_TAG))
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
                    device.wait(Until.hasObject(By.res(PACKAGE_NAME, HRV_CHART_TAG)), WAIT_TIMEOUT_MS)
                    device.findObject(By.text("Dashboard"))?.click()
                        ?: error("Dashboard nav item not found")
                    device.waitForIdle()
                }
            },
        )
}

private fun MacrobenchmarkScope.navigateToVitals(device: UiDevice) {
    device.wait(Until.hasObject(By.text("Vitals")), WAIT_TIMEOUT_MS)
    device.findObject(By.text("Vitals"))?.click() ?: error("Vitals nav item not found")
    device.wait(Until.hasObject(By.res(PACKAGE_NAME, HRV_CHART_TAG)), WAIT_TIMEOUT_MS)
}
