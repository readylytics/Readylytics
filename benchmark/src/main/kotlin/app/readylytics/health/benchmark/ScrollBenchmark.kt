package app.readylytics.health.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val JOURNEY_ITERATIONS = 10

@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Before
    fun grantHealthConnectPermissions() {
        grantHealthConnectPermissions(MACROBENCHMARK_PACKAGE_NAME)
    }

    @Test
    fun vitalsFling() =
        benchmarkRule.measureRepeated(
            packageName = MACROBENCHMARK_PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = JOURNEY_ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                navigateToTab(appString(MACROBENCHMARK_PACKAGE_NAME, "tab_vitals"))
                waitForNonEmptyChart(HRV_CHART_TAG, MACROBENCHMARK_PACKAGE_NAME)
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
            packageName = MACROBENCHMARK_PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = JOURNEY_ITERATIONS,
            setupBlock = {
                startActivityAndWait()
                navigateToTab(appString(MACROBENCHMARK_PACKAGE_NAME, "tab_vitals"))
                waitForNonEmptyChart(HRV_CHART_TAG, MACROBENCHMARK_PACKAGE_NAME)
                selectThirtyDayRange()
                waitForNonEmptyChart(HRV_CHART_TAG, MACROBENCHMARK_PACKAGE_NAME)
            },
            measureBlock = {
                panAndZoomChart(HRV_CHART_TAG)
            },
        )

    @Test
    fun dashboardVitalsTabSwitch() =
        benchmarkRule.measureRepeated(
            packageName = MACROBENCHMARK_PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            iterations = JOURNEY_ITERATIONS,
            setupBlock = { startActivityAndWait() },
            measureBlock = {
                repeat(3) {
                    navigateToTab(appString(MACROBENCHMARK_PACKAGE_NAME, "tab_vitals"))
                    waitForNonEmptyChart(HRV_CHART_TAG, MACROBENCHMARK_PACKAGE_NAME)
                    navigateToTab(appString(MACROBENCHMARK_PACKAGE_NAME, "tab_dashboard"))
                    waitForDashboard()
                }
            },
        )
}
