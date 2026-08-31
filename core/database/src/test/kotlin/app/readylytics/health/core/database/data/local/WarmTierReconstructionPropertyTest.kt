package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

class WarmTierReconstructionPropertyTest {
    // Measured (seed=42, mean absolute error over the *entire* sorted reconstruction vs. sorted
    // raw sample list):
    //   n=5:  percentile=1.60  3-point=2.40  flat-mean=6.80
    //   n=10: percentile=1.10  3-point=4.30  flat-mean=7.20
    //   n=25: percentile=1.04  3-point=6.16  flat-mean=7.48
    //   n=50: percentile=0.60  3-point=5.88  flat-mean=6.56
    // Percentile-sketch strictly beats 3-point at every n here; 3-point strictly beats flat-mean
    // at every n (the 3-point method's min/max endpoints are exact by construction, which
    // flat-mean can never match).
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
            // Matches the reconstructor's own flat-mean rounding (round-half-up via kotlin.math.round),
            // not raw truncation -- the reconstructor never truncates, so a truncated baseline would
            // compare against a rounding mode WarmTierReconstructor doesn't actually use.
            val flatMeanValues = List(n) { round(raw.average()).toInt() }

            // A single order statistic (e.g. just the reconstructed median) can't reliably
            // distinguish the three methods -- and for the 3-point method specifically, the
            // median index always lands inside its flat `avg` run, making a median-only
            // 3-point-vs-flat-mean comparison a tautology regardless of reconstruction quality.
            // Compare the *entire* sorted reconstruction against the entire sorted raw sample
            // list instead: mean absolute error, order statistic by order statistic.
            val percentileMae = meanAbsoluteError(listOf(richBucket).reconstructSampleValues(), raw)
            val threePointMae = meanAbsoluteError(listOf(threePointBucket).reconstructSampleValues(), raw)
            val flatMeanMae = meanAbsoluteError(flatMeanValues, raw)

            assertTrue(
                "n=$n: percentile MAE $percentileMae should be <= 3-point MAE $threePointMae",
                percentileMae <= threePointMae,
            )
            assertTrue(
                "n=$n: 3-point MAE $threePointMae should be <= flat-mean MAE $flatMeanMae",
                threePointMae <= flatMeanMae,
            )
        }
    }

    /** Mean absolute error between two equal-length sample sets, compared order statistic by order statistic. */
    private fun meanAbsoluteError(
        reconstructed: List<Int>,
        raw: List<Int>,
    ): Double = reconstructed.sorted().zip(raw.sorted()).map { (r, a) -> abs(r - a) }.average()
}
