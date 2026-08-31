package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

class WarmTierReconstructionPropertyTest {
    @Test
    fun `percentile reconstruction is at least as accurate as 3-point, which beats flat-mean`() {
        val random = Random(seed = 42)
        for (n in listOf(5, 10, 25, 50)) {
            val raw = (0 until n).map { i -> 50 + random.nextInt(30) + (i % 7) } // synthetic, non-uniform
            val samples =
                raw.mapIndexed { i, bpm ->
                    HeartRateRecordEntity(1L, i * 1_000L, bpm, "SLEEP", "s1")
                }
            val richBucket = samples.aggregateIntoMinuteBuckets().single()
            val threePointBucket =
                richBucket.copy(p5Bpm = null, p25Bpm = null, p50Bpm = null, p75Bpm = null, p95Bpm = null)

            val rawP50 = raw.sorted()[raw.size / 2]
            val percentileError = abs(listOf(richBucket).reconstructSampleValues().sorted()[n / 2] - rawP50)
            val threePointError = abs(listOf(threePointBucket).reconstructSampleValues().sorted()[n / 2] - rawP50)
            // Matches the reconstructor's own flat-mean rounding (round-half-up via kotlin.math.round),
            // not raw truncation -- otherwise this baseline silently compares against a different,
            // more-favorable rounding mode than what WarmTierReconstructor actually emits, which makes
            // this assertion flaky (observed: n=50/seed=42 fails under truncation, avgBpm=65.96 rounds
            // to 66 but truncates to 65).
            val flatMeanError = abs(round(raw.average()).toInt() - rawP50)

            assertTrue(
                "n=$n: percentile error $percentileError should be <= 3-point error $threePointError",
                percentileError <= threePointError,
            )
            assertTrue(
                "n=$n: 3-point error $threePointError should be <= flat-mean error $flatMeanError",
                threePointError <= flatMeanError,
            )
        }
    }
}
