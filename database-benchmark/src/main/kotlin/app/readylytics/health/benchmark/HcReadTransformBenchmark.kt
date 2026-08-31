package app.readylytics.health.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.sync.mappers.HeartRateMapper
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** B1 (R2-PERF-004): mapping one 5,000-sample HC page through HeartRateMapper. */
@RunWith(AndroidJUnit4::class)
class HcReadTransformBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val page: List<DomainHeartRateRecord> = buildPage()

    @Test
    fun mapFiveThousandSamplePage() {
        var lastSize = 0
        benchmarkRule.measureRepeated {
            val inputs = HeartRateMapper.mapToInputs(page, emptyList(), emptyList())
            lastSize = inputs.size
        }
        BenchmarkFixtures.recordAllocationDelta("B1.mapFiveThousandSamplePage") {
            HeartRateMapper.mapToInputs(page, emptyList(), emptyList())
        }
        assertEquals(5_000, lastSize)
    }

    private fun buildPage(): List<DomainHeartRateRecord> {
        val base = Instant.parse("2026-01-10T22:00:00Z")
        return (0 until 50).map { recordIndex ->
            DomainHeartRateRecord(
                id = "hc-$recordIndex",
                deviceName = "bench-device",
                samples =
                    (0 until 100).map { sampleIndex ->
                        DomainHeartRateSample(
                            time = base.plusSeconds((recordIndex * 100 + sampleIndex).toLong()),
                            beatsPerMinute = 55 + (sampleIndex % 30),
                        )
                    },
            )
        }
    }
}
