package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.HrMinuteBucketRow
import app.readylytics.health.domain.scoring.LongInterval
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PERF-006/WP-21: fixtures here express already SQL-bucketed rows (`HeartRateDao.getMinuteBuckets`'
 * output shape), not raw per-sample rows -- the plausibility filter (30-230 bpm) and the per-minute
 * average are applied by SQL before the calculator ever sees a row, so a fixture representing
 * "implausible samples" is simply the absence of a bucket row for that minute, and a fixture
 * representing "multiple samples in one minute" is a single row with `sampleCount > 1` and the
 * pre-averaged `avgBpm`.
 */
class EverydayHeartRateLoadCalculatorTest {
    private val defaultPrefs =
        UserPreferences(
            gender = Gender.MALE,
            maxHeartRate = 190,
            autoCalculateMaxHr = false,
            rhrBaselineOverride = 60f,
            trimpModel = TrimpModel.BANISTER,
            banisterMultiplier = 1.0f,
            // defaults: zone1MinBpm=95, zone1MaxBpm=114, zone2MaxBpm=133, zone3MaxBpm=152, zone4MaxBpm=171
        )

    private val rhrBaseline = 60f
    private val hrMax = 190f

    private val dayStartMs = 0L
    private val dayEndMs = 24L * 60L * 60_000L // full day in ms

    private fun bucket(
        bucketIndex: Int,
        avgBpm: Double,
        sampleCount: Int = 1,
    ) = HrMinuteBucketRow(bucketIndex, avgBpm, sampleCount)

    /** Builds consecutive Zone-0 (e.g. bpm=70) single-sample buckets for [count] minutes starting at minute 0. */
    private fun zone0Buckets(count: Int): List<HrMinuteBucketRow> =
        (0 until count).map { minute -> bucket(minute, 70.0) }

    private fun baseInput(
        hrBuckets: List<HrMinuteBucketRow>,
        sleepIntervalsMs: List<LongInterval> = emptyList(),
        workoutIntervalsMs: List<LongInterval> = emptyList(),
        workoutOnlyTrimp: Float = 0f,
    ) = EverydayHrLoadInput(
        dayStartMs = dayStartMs,
        dayEndMs = dayEndMs,
        hrBuckets = hrBuckets,
        sleepIntervalsMs = sleepIntervalsMs,
        workoutIntervalsMs = workoutIntervalsMs,
        workoutOnlyTrimp = workoutOnlyTrimp,
        rhrBaseline = rhrBaseline,
        hrMax = hrMax,
        prefs = defaultPrefs,
    )

    @Test
    fun `sleep interval HR bucket excluded entirely`() {
        // Minute-0 bucket falls within a sleep interval covering [0, 60_000)
        val input =
            baseInput(
                hrBuckets = listOf(bucket(0, 130.0)),
                sleepIntervalsMs = listOf(LongInterval(0L, 60_000L)),
            )

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(0, result.coverageMinutes)
        assertEquals(0, result.validBucketCount)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
        assertEquals(LoadCoverageConfidence.NONE, result.confidence)
        assertFalse(result.valid)
    }

    @Test
    fun `workout interval HR bucket excluded but workoutOnlyTrimp counted exactly once`() {
        // Minute-0 bucket falls within a workout interval covering [0, 60_000)
        val workoutOnlyTrimp = 12.5f
        val input =
            baseInput(
                hrBuckets = listOf(bucket(0, 160.0)),
                workoutIntervalsMs = listOf(LongInterval(0L, 60_000L)),
                workoutOnlyTrimp = workoutOnlyTrimp,
            )

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(0, result.coverageMinutes)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
        assertEquals(workoutOnlyTrimp, result.totalEverydayTrimp, 0.001f)
    }

    @Test
    fun `zone 0 minute counts toward coverage but contributes zero TRIMP`() {
        // bpm=70 is below zone1MinBpm=95 -> zone 0
        val input = baseInput(hrBuckets = listOf(bucket(0, 70.0)))

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(1, result.coverageMinutes)
        assertEquals(0, result.validBucketCount)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
    }

    @Test
    fun `zone 1 or higher minute increments coverage and validBucketCount with positive TRIMP`() {
        // bpm=130 -> zone 2 (95 < bpm <= 133)
        val input = baseInput(hrBuckets = listOf(bucket(0, 130.0)))

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(1, result.coverageMinutes)
        assertEquals(1, result.validBucketCount)
        assertTrue(result.nonWorkoutTrimp > 0f)
        assertEquals(result.nonWorkoutTrimp, result.totalEverydayTrimp, 0.001f)
    }

    @Test
    fun `bucket sampleCount does not affect zone or TRIMP calc, only avgBpm does`() {
        // SQL already averages same-minute samples (two raw samples at 120 and 140 -> avgBpm 130,
        // sampleCount 2); the calculator must treat that identically to a single 130 bpm sample.
        val multiSampleInput = baseInput(hrBuckets = listOf(bucket(0, 130.0, sampleCount = 2)))
        val singleSampleInput = baseInput(hrBuckets = listOf(bucket(0, 130.0, sampleCount = 1)))

        val multiSampleResult = EverydayHeartRateLoadCalculator.calculate(multiSampleInput)
        val singleSampleResult = EverydayHeartRateLoadCalculator.calculate(singleSampleInput)

        assertEquals(1, multiSampleResult.coverageMinutes)
        assertEquals(1, multiSampleResult.validBucketCount)
        assertEquals(singleSampleResult.nonWorkoutTrimp, multiSampleResult.nonWorkoutTrimp, 0.001f)
    }

    @Test
    fun `no HR buckets at all`() {
        val workoutOnlyTrimp = 7.5f
        val input = baseInput(hrBuckets = emptyList(), workoutOnlyTrimp = workoutOnlyTrimp)

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(0, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.NONE, result.confidence)
        assertFalse(result.valid)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
        assertEquals(workoutOnlyTrimp, result.totalEverydayTrimp, 0.001f)
    }

    @Test
    fun `implausible samples never produce a bucket row and so do not affect coverage`() {
        // bpm=20 and bpm=250 are outside 30..230 -- SQL's WHERE clause excludes both before
        // GROUP BY, so minute 0 has no row at all (not a zero-count or zero-avg row).
        val input = baseInput(hrBuckets = emptyList())

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(0, result.coverageMinutes)
        assertEquals(0, result.validBucketCount)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
    }

    @Test
    fun `confidence boundary 0 minutes maps to NONE`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrBuckets = emptyList()))

        assertEquals(0, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.NONE, result.confidence)
        assertFalse(result.valid)
    }

    @Test
    fun `confidence boundary 179 minutes maps to LOW`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrBuckets = zone0Buckets(179)))

        assertEquals(179, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.LOW, result.confidence)
        assertFalse(result.valid)
    }

    @Test
    fun `confidence boundary 180 minutes maps to MEDIUM and marks valid`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrBuckets = zone0Buckets(180)))

        assertEquals(180, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.MEDIUM, result.confidence)
        assertTrue(result.valid)
    }

    @Test
    fun `confidence boundary 479 minutes maps to MEDIUM`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrBuckets = zone0Buckets(479)))

        assertEquals(479, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.MEDIUM, result.confidence)
        assertTrue(result.valid)
    }

    @Test
    fun `confidence boundary 480 minutes maps to HIGH`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrBuckets = zone0Buckets(480)))

        assertEquals(480, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.HIGH, result.confidence)
        assertTrue(result.valid)
    }

    @Test
    fun `bucket adjacent to sleep interval is not excluded`() {
        // Sleep interval [0, 60_000) covers minute 0 only. A bucket at minute 1 [60_000, 120_000)
        // is adjacent but not overlapping, so it should be counted.
        val input =
            baseInput(
                hrBuckets = listOf(bucket(1, 130.0)),
                sleepIntervalsMs = listOf(LongInterval(0L, 60_000L)),
            )

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(1, result.coverageMinutes)
        assertEquals(1, result.validBucketCount)
        assertTrue(result.nonWorkoutTrimp > 0f)
    }

    @Test
    fun `bucket with partial overlap with sleep interval is excluded`() {
        // Sleep interval [30_000, 90_000) partially overlaps minute 0 [0, 60_000) and minute 1 [60_000, 120_000).
        // Both buckets should be excluded.
        val input =
            baseInput(
                hrBuckets = listOf(bucket(0, 130.0), bucket(1, 130.0)),
                sleepIntervalsMs = listOf(LongInterval(30_000L, 90_000L)),
            )

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(0, result.coverageMinutes)
        assertEquals(0, result.validBucketCount)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
    }
}
