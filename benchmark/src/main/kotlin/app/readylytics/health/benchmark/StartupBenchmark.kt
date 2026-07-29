package app.readylytics.health.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartCompilationNone() {
        measureColdStart(CompilationMode.None())
    }

    @Test
    fun coldStartCompilationBaselineProfile() {
        measureColdStart(
            CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
            ),
        )
    }

    private fun measureColdStart(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = MACROBENCHMARK_PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            iterations = 3,
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait() },
        )
    }

    @Test
    fun warmStart() =
        benchmarkRule.measureRepeated(
            packageName = MACROBENCHMARK_PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                startActivityAndWait()
                pressHome()
            },
            measureBlock = { startActivityAndWait() },
        )

    @Test
    fun hotStart() =
        benchmarkRule.measureRepeated(
            packageName = MACROBENCHMARK_PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            iterations = 10,
            startupMode = StartupMode.HOT,
            setupBlock = { startActivityAndWait() },
            measureBlock = {
                pressHome()
                startActivityAndWait()
            },
        )
}
