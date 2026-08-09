package app.readylytics.health.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

private const val ARG_MACHINE_ID_SEGMENT = "readylytics.machineIdSegment"

internal val MACROBENCHMARK_PACKAGE_NAME: String =
    "app.readylytics.health.local." +
        requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_MACHINE_ID_SEGMENT)) {
            "Instrumentation argument '$ARG_MACHINE_ID_SEGMENT' missing — " +
                "set it in :benchmark defaultConfig.testInstrumentationRunnerArguments"
        }
internal const val BASELINE_PROFILE_PACKAGE_NAME = "app.readylytics.health.baselineprofile"
internal const val DASHBOARD_ROOT_TAG = "dashboard_lazy_column"
internal const val SLEEP_CHART_TAG = "SleepTrendChart"
internal const val HRV_CHART_TAG = "HrvTrendChart"
internal const val ACWR_CHART_TAG = "AcwrChart"
internal const val THIRTY_DAY_RANGE_LABEL = "30D"

private const val WAIT_TIMEOUT_MS = 15_000L
private const val MAX_VERTICAL_SCROLLS = 8

private val requiredHealthConnectPermissions =
    listOf(
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_HEART_RATE_VARIABILITY",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_HEALTH_DATA_HISTORY",
    )

internal fun grantHealthConnectPermissions(packageName: String) {
    val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    requiredHealthConnectPermissions.forEach { permission ->
        runCatching { uiAutomation.grantRuntimePermission(packageName, permission) }
            .getOrElse { cause ->
                error("Failed to grant $permission to $packageName: ${cause.message}")
            }
    }
}

internal fun appString(
    packageName: String,
    resourceName: String,
): String {
    val instrumentationContext = InstrumentationRegistry.getInstrumentation().context
    val packageContext =
        runCatching { instrumentationContext.createPackageContext(packageName, 0) }
            .getOrElse { cause ->
                error("Cannot load resources for $packageName: ${cause.message}")
            }
    val resourceId =
        packageContext.resources.getIdentifier(resourceName, "string", packageName)
    check(resourceId != 0) { "String resource $resourceName not found in $packageName" }
    return packageContext.getString(resourceId)
}

private fun MacrobenchmarkScope.waitForObject(
    selector: BySelector,
    failureMessage: String,
): UiObject2 {
    check(device.wait(Until.hasObject(selector), WAIT_TIMEOUT_MS)) { failureMessage }
    return device.findObject(selector) ?: error(failureMessage)
}

internal fun MacrobenchmarkScope.waitForDashboard() {
    waitForObject(By.res(DASHBOARD_ROOT_TAG), "Dashboard content did not render")
}

internal fun MacrobenchmarkScope.navigateToTab(label: String) {
    waitForObject(By.text(label), "$label tab not found").click()
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.selectThirtyDayRange() {
    val selector = By.text(THIRTY_DAY_RANGE_LABEL)
    repeat(MAX_VERTICAL_SCROLLS) {
        device.findObject(selector)?.let { rangeSelector ->
            rangeSelector.click()
            device.waitForIdle()
            return
        }
        val scrollable =
            device.findObject(By.scrollable(true))
                ?: error("Scrollable container not found while revealing $THIRTY_DAY_RANGE_LABEL selector")
        scrollable.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle()
    }
    device.findObject(selector)?.let { rangeSelector ->
        rangeSelector.click()
        device.waitForIdle()
        return
    }
    error("$THIRTY_DAY_RANGE_LABEL selector not found after $MAX_VERTICAL_SCROLLS vertical scroll attempts")
}

internal fun MacrobenchmarkScope.revealChart(tag: String) {
    repeat(MAX_VERTICAL_SCROLLS) {
        if (device.hasObject(By.res(tag))) return
        val scrollable =
            device.findObject(By.scrollable(true))
                ?: error("Scrollable container not found while revealing $tag")
        scrollable.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle()
    }
    error("$tag not found after $MAX_VERTICAL_SCROLLS vertical scroll attempts")
}

internal fun MacrobenchmarkScope.waitForNonEmptyChart(
    tag: String,
    packageName: String,
) {
    waitForObject(By.res(tag), "$tag chart not found")
    val noDataText = appString(packageName, "message_no_data_available")
    val emptyChart = By.res(tag).hasDescendant(By.text(noDataText))
    check(device.wait(Until.gone(emptyChart), WAIT_TIMEOUT_MS)) {
        "$tag still displays its empty state; benchmark seeding may have failed"
    }
}

internal fun MacrobenchmarkScope.panAndZoomChart(tag: String) {
    val chart = waitForObject(By.res(tag), "$tag chart not found for gestures")
    val bounds = chart.visibleBounds
    check(bounds.width() > 0 && bounds.height() > 0) { "$tag has empty visible bounds" }
    val inset = (bounds.width() / 8).coerceAtLeast(1)
    val centerY = bounds.centerY()

    device.swipe(bounds.right - inset, centerY, bounds.left + inset, centerY, 20)
    device.waitForIdle()
    device.swipe(bounds.left + inset, centerY, bounds.right - inset, centerY, 20)
    device.waitForIdle()
    chart.pinchOpen(0.8f, 200)
    device.waitForIdle()
    chart.pinchClose(0.8f, 200)
    device.waitForIdle()
}

internal fun MacrobenchmarkScope.waitForSettingsContent(packageName: String) {
    val dataAndBackup = appString(packageName, "settings_section_data_backup")
    waitForObject(By.text(dataAndBackup), "Settings content did not render")
}
