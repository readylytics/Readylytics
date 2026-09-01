package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

/**
 * Each of the two reconstruction-quality upgrades (R2-DB-004) makes a different kind of claim,
 * so each gets its own test rather than one test with two loosely-related assertions:
 *
 * - 3-point vs. flat-mean is a **deterministic, by-construction** guarantee: [reconstructThreePoint]
 *   emits the exact raw min/max at its two endpoints instead of `round(avg)`, so it can never do
 *   worse than flat-mean, and strictly better whenever there's an actual outlier to correct. This
 *   is not an empirical discovery -- it's a regression guard for that construction.
 * - Percentile-sketch vs. 3-point is only true **in aggregate / expectation**, not for every single
 *   small-sample draw (a lucky `round(avg)` can occasionally out-MAE the sketch on a single n=5
 *   bucket). Tested by averaging over many random buckets per `n`, not a single seeded draw.
 */
class WarmTierReconstructionPropertyTest {
    // By construction, not by luck: reconstructThreePoint() emits [min, round(avg)x(n-2), max],
    // identical to a flat-mean list of round(avg) at every position except the two endpoints,
    // where it substitutes the exact raw min/max instead of round(avg). So threePointMae <=
    // flatMeanMae for ANY input, always -- and strictly less whenever min or max actually differs
    // from round(avg) (i.e. there's a real outlier for the exact endpoints to correct). This test
    // exists to catch a regression where someone routes the endpoints through round(avg) too
    // (which would collapse the inequality to equality), not to "discover" reconstruction quality.
    @Test
    fun `3-point reconstruction's exact endpoints strictly beat flat-mean whenever there's an outlier to correct`() {
        val random = Random(seed = 42)
        for (n in listOf(5, 10, 25, 50)) {
            val raw = (0 until n).map { i -> 50 + random.nextInt(30) + (i % 7) } // synthetic, non-uniform
            val richBucket = raw.toMinuteBucket()
            val threePointBucket = richBucket.withoutPercentiles()
            val roundedAvg = round(richBucket.avgBpm).toInt()
            val flatMeanValues = List(n) { roundedAvg }

            val hasOutlierToCorrect = richBucket.minBpm != roundedAvg || richBucket.maxBpm != roundedAvg
            assertTrue(
                "n=$n: fixture must have min or max != round(avg), otherwise this test asserts nothing",
                hasOutlierToCorrect,
            )

            val threePointMae = meanAbsoluteError(listOf(threePointBucket).reconstructSampleValues(), raw)
            val flatMeanMae = meanAbsoluteError(flatMeanValues, raw)
            assertTrue(
                "n=$n: 3-point MAE $threePointMae should be strictly < flat-mean MAE $flatMeanMae " +
                    "(exact min/max endpoints vs. round(avg) endpoints)",
                threePointMae < flatMeanMae,
            )
        }
    }

    // Percentile-sketch beats 3-point in expectation, not in every single small-sample realization
    // -- a lucky round(avg) can occasionally out-MAE the sketch on one n=5 draw. Averaging MAE
    // across many random buckets per n (rather than asserting on one seeded draw) tests the claim
    // that's actually true and keeps the test honest about what it's checking.
    @Test
    fun `percentile-sketch reconstruction has lower mean error than 3-point, averaged over many random buckets`() {
        val random = Random(seed = 42)
        for (n in listOf(5, 10, 25, 50)) {
            var percentileMaeSum = 0.0
            var threePointMaeSum = 0.0
            repeat(TRIALS_PER_N) {
                val raw = (0 until n).map { i -> 50 + random.nextInt(30) + (i % 7) }
                val richBucket = raw.toMinuteBucket()
                val threePointBucket = richBucket.withoutPercentiles()
                percentileMaeSum += meanAbsoluteError(listOf(richBucket).reconstructSampleValues(), raw)
                threePointMaeSum += meanAbsoluteError(listOf(threePointBucket).reconstructSampleValues(), raw)
            }
            val avgPercentileMae = percentileMaeSum / TRIALS_PER_N
            val avgThreePointMae = threePointMaeSum / TRIALS_PER_N
            assertTrue(
                "n=$n over $TRIALS_PER_N random buckets: avg percentile MAE $avgPercentileMae " +
                    "should be <= avg 3-point MAE $avgThreePointMae",
                avgPercentileMae <= avgThreePointMae,
            )
        }
    }

    private fun List<Int>.toMinuteBucket() =
        mapIndexed { i, bpm -> HeartRateRecordEntity(1L, i * 1_000L, bpm, "SLEEP", "s1") }
            .aggregateIntoMinuteBuckets()
            .single()

    private fun HrMinuteBucketEntity.withoutPercentiles() =
        copy(p5Bpm = null, p25Bpm = null, p50Bpm = null, p75Bpm = null, p95Bpm = null)

    /** Mean absolute error between two equal-length sample sets, compared order statistic by order statistic. */
    private fun meanAbsoluteError(
        reconstructed: IntArray,
        raw: List<Int>,
    ): Double = meanAbsoluteError(reconstructed.toList(), raw)

    private fun meanAbsoluteError(
        reconstructed: List<Int>,
        raw: List<Int>,
    ): Double = reconstructed.sorted().zip(raw.sorted()).map { (r, a) -> abs(r - a) }.average()

    private companion object {
        const val TRIALS_PER_N = 300
    }
}
