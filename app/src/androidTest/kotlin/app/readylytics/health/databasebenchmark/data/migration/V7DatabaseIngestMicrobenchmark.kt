package app.readylytics.health.databasebenchmark.data.migration

import androidx.benchmark.ExperimentalBenchmarkConfigApi
import androidx.benchmark.MicrobenchmarkConfig
import androidx.benchmark.junit4.BenchmarkRule
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.core.database.data.local.HealthDatabase
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// `:app`'s androidTest uses the plain AndroidJUnitRunner, under which androidx.benchmark's
// suppressErrors instrumentation argument is never read (that mechanism only activates under
// androidx.benchmark.junit4.AndroidBenchmarkRunner, which :database-benchmark uses). These
// benchmarks can't live in :database-benchmark instead: they exercise :app's internal
// V7DatabaseMigrator constructor, which is only visible within :app's own compilation unit.
// So on this runner these tests would always hard-fail on the DEBUGGABLE/NOT-AOT-COMPILED
// environment checks. @LargeTest + :app's `notAnnotation` filter keeps them out of the
// routine connectedDebugAndroidTest sweep; run explicitly with
//   ./gradlew :app:connectedDebugAndroidTest \
//     -Pandroid.testInstrumentationRunnerArguments.annotation=androidx.test.filters.LargeTest
@LargeTest
@OptIn(ExperimentalBenchmarkConfigApi::class)
@RunWith(AndroidJUnit4::class)
class V7DatabaseIngestMicrobenchmark {
    @get:Rule(order = 0)
    val benchmarkRule =
        BenchmarkRule(
            MicrobenchmarkConfig(
                warmupCount = 2,
                measurementCount = 8,
            ),
        )

    @get:Rule(order = 1)
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HealthDatabase::class.java,
        )

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val fixtures by lazy { DatabaseBenchmarkFixture(context, helper) }

    @After
    fun cleanUp() {
        fixtures.cleanUp()
    }

    @Test
    fun v6FiveThousandRowIngest() {
        benchmarkFreshClone(version = 6)
    }

    @Test
    fun v7FiveThousandRowIngest() {
        benchmarkFreshClone(version = 7)
    }

    private fun benchmarkFreshClone(version: Int) {
        val template = fixtures.createTemplate(version, "micro-v$version-template")
        val state = benchmarkRule.getState()
        var iteration = 0
        while (state.keepRunning()) {
            state.pauseTiming()
            val fixture = fixtures.copyTemplate(template, "micro-v$version-${iteration++}")
            fixture.driver.withWritableDatabase { database ->
                state.resumeTiming()
                fixtures.insertTimedBatch(database)
                state.pauseTiming()
            }
            fixtures.delete(fixture)
            state.resumeTiming()
        }
    }
}
