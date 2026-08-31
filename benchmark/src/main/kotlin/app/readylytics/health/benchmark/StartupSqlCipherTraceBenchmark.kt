package app.readylytics.health.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** B10 (R2-PERF-005): does SQLCipher/Keystore work appear in the cold-start trace? */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class StartupSqlCipherTraceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartTraceSections() =
        benchmarkRule.measureRepeated(
            packageName = MACROBENCHMARK_PACKAGE_NAME,
            metrics =
                listOf(
                    StartupTimingMetric(),
                    TraceSectionMetric("Readylytics.sqlcipherMigrateIfNeeded", TraceSectionMetric.Mode.Sum),
                    TraceSectionMetric("Readylytics.provideDatabase", TraceSectionMetric.Mode.Sum),
                ),
            iterations = 3,
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait() },
        )
}
