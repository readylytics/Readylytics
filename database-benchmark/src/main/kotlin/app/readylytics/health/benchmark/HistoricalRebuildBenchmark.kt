package app.readylytics.health.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.benchmark.BenchmarkTestEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * B6 + B7: HealthSyncUseCase.recomputeRange (skipIngestAndPrune=true) — 8-day and 365-day baselines.
 * recomputeRange is the parent plan's `resyncRange(skipIngestAndPrune = true)` body and needs no
 * Health Connect source; it runs against the app's real (benchmark-install) database via the Hilt
 * [BenchmarkTestEntryPoint].
 */
@RunWith(AndroidJUnit4::class)
class HistoricalRebuildBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var entryPoint: BenchmarkTestEntryPoint

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        entryPoint = EntryPointAccessors.fromApplication(context, BenchmarkTestEntryPoint::class.java)
    }

    @Test
    fun incrementalRecomputeEightDays() {
        val start = LocalDate.of(2026, 1, 1)
        val end = LocalDate.of(2026, 1, 8)
        benchmarkRule.measureRepeated {
            runBlocking {
                entryPoint.healthSyncUseCase().recomputeRange(startDate = start, endDate = end, onProgress = null)
            }
        }
        BenchmarkFixtures.recordAllocationDelta("B6.incrementalRecomputeEightDays") {
            runBlocking {
                entryPoint.healthSyncUseCase().recomputeRange(startDate = start, endDate = end, onProgress = null)
            }
        }
        BenchmarkFixtures.recordAllocationDelta("B7.historicalRebuildThreeSixtyFiveDays") {
            runBlocking {
                entryPoint.healthSyncUseCase().recomputeRange(startDate = start, endDate = end, onProgress = null)
            }
        }
    }

    @Test
    fun historicalRebuildThreeSixtyFiveDays() {
        val start = LocalDate.of(2025, 1, 1)
        val end = LocalDate.of(2025, 12, 31)
        benchmarkRule.measureRepeated {
            runBlocking {
                entryPoint.healthSyncUseCase().recomputeRange(startDate = start, endDate = end, onProgress = null)
            }
        }
    }
}
