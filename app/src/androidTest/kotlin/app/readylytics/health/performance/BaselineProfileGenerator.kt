package app.readylytics.health.performance

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run on a connected device/emulator to (re)generate the baseline profile:
 *   ./gradlew :app:connectedBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile \
 *     --tests "app.readylytics.health.performance.BaselineProfileGenerator"
 *
 * Then copy the resulting `*-baseline-prof.txt` from
 * app/build/outputs/connected_android_test_additional_output/.../ to
 * app/src/main/baselineProfiles/baseline-prof.txt — AGP picks that file up automatically
 * and bakes it into the APK so ProfileInstaller can trigger AOT compilation of the
 * recorded startup/dashboard path on install.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() =
        baselineProfileRule.collect(packageName = "app.readylytics.health") {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }
}
