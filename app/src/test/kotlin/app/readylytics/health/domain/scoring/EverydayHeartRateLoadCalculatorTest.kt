package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase.HeartRateSample
import app.readylytics.health.domain.scoring.LongInterval
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun sample(
        epochMs: Long,
        bpm: Int,
    ) = HeartRateSample(Instant.ofEpochMilli(epochMs), bpm)

    /** Builds consecutive 1-minute Zone-0 (e.g. bpm=70) samples for [count] minutes starting at minute 0. */
    private fun zone0Samples(count: Int): List<HeartRateSample> =
        (0 until count).map { minute -> sample(minute * 60_000L, 70) }

    private fun baseInput(
        hrSamples: List<HeartRateSample>,
        sleepIntervalsMs: List<LongInterval> = emptyList(),
        workoutIntervalsMs: List<LongInterval> = emptyList(),
        workoutOnlyTrimp: Float = 0f,
    ) = EverydayHrLoadInput(
        dayStartMs = dayStartMs,
        dayEndMs = dayEndMs,
        hrSamples = hrSamples,
        sleepIntervalsMs = sleepIntervalsMs,
        workoutIntervalsMs = workoutIntervalsMs,
        workoutOnlyTrimp = workoutOnlyTrimp,
        rhrBaseline = rhrBaseline,
        hrMax = hrMax,
        prefs = defaultPrefs,
    )

    @Test
    fun `sleep interval HR samples excluded entirely`() {
        // Minute 0 sample falls within a sleep interval covering [0, 60_000)
        val input =
            baseInput(
                hrSamples = listOf(sample(0L, 130)),
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
    fun `workout interval HR samples excluded but workoutOnlyTrimp counted exactly once`() {
        // Minute 0 sample falls within a workout interval covering [0, 60_000)
        val workoutOnlyTrimp = 12.5f
        val input =
            baseInput(
                hrSamples = listOf(sample(0L, 160)),
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
        val input = baseInput(hrSamples = listOf(sample(0L, 70)))

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(1, result.coverageMinutes)
        assertEquals(0, result.validBucketCount)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
    }

    @Test
    fun `zone 1 or higher minute increments coverage and validBucketCount with positive TRIMP`() {
        // bpm=130 -> zone 2 (95 < bpm <= 133)
        val input = baseInput(hrSamples = listOf(sample(0L, 130)))

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(1, result.coverageMinutes)
        assertEquals(1, result.validBucketCount)
        assertTrue(result.nonWorkoutTrimp > 0f)
        assertEquals(result.nonWorkoutTrimp, result.totalEverydayTrimp, 0.001f)
    }

    @Test
    fun `multiple samples in same minute bucket are averaged before zone and TRIMP calc`() {
        // Two samples in minute 0: 120 and 140 -> average 130 -> zone 2
        val averagedInput =
            baseInput(
                hrSamples =
                    listOf(
                        sample(0L, 120),
                        sample(30_000L, 140),
                    ),
            )
        val singleInput = baseInput(hrSamples = listOf(sample(0L, 130)))

        val averagedResult = EverydayHeartRateLoadCalculator.calculate(averagedInput)
        val singleResult = EverydayHeartRateLoadCalculator.calculate(singleInput)

        assertEquals(1, averagedResult.coverageMinutes)
        assertEquals(1, averagedResult.validBucketCount)
        assertEquals(singleResult.nonWorkoutTrimp, averagedResult.nonWorkoutTrimp, 0.001f)
    }

    @Test
    fun `no HR samples at all`() {
        val workoutOnlyTrimp = 7.5f
        val input = baseInput(hrSamples = emptyList(), workoutOnlyTrimp = workoutOnlyTrimp)

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(0, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.NONE, result.confidence)
        assertFalse(result.valid)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
        assertEquals(workoutOnlyTrimp, result.totalEverydayTrimp, 0.001f)
    }

    @Test
    fun `implausible samples are discarded and do not affect bucket averages or coverage`() {
        // bpm=20 and bpm=250 are outside 30..230 and should be dropped entirely.
        val input =
            baseInput(
                hrSamples =
                    listOf(
                        sample(0L, 20),
                        sample(30_000L, 250),
                    ),
            )

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(0, result.coverageMinutes)
        assertEquals(0, result.validBucketCount)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
    }

    @Test
    fun `confidence boundary 0 minutes maps to NONE`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrSamples = emptyList()))

        assertEquals(0, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.NONE, result.confidence)
        assertFalse(result.valid)
    }

    @Test
    fun `confidence boundary 179 minutes maps to LOW`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrSamples = zone0Samples(179)))

        assertEquals(179, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.LOW, result.confidence)
        assertFalse(result.valid)
    }

    @Test
    fun `confidence boundary 180 minutes maps to MEDIUM and marks valid`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrSamples = zone0Samples(180)))

        assertEquals(180, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.MEDIUM, result.confidence)
        assertTrue(result.valid)
    }

    @Test
    fun `confidence boundary 479 minutes maps to MEDIUM`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrSamples = zone0Samples(479)))

        assertEquals(479, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.MEDIUM, result.confidence)
        assertTrue(result.valid)
    }

    @Test
    fun `confidence boundary 480 minutes maps to HIGH`() {
        val result = EverydayHeartRateLoadCalculator.calculate(baseInput(hrSamples = zone0Samples(480)))

        assertEquals(480, result.coverageMinutes)
        assertEquals(LoadCoverageConfidence.HIGH, result.confidence)
        assertTrue(result.valid)
    }

    @Test
    fun `sample exactly at bucket boundary falls into the later bucket`() {
        // Sample at exactly 60_000ms (start of minute 1, not end of minute 0).
        val input = baseInput(hrSamples = listOf(sample(60_000L, 130)))

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        // The sample contributes to exactly one bucket (minute 1), not minute 0.
        assertEquals(1, result.coverageMinutes)
        assertEquals(1, result.validBucketCount)
    }

    @Test
    fun `bucket adjacent to sleep interval is not excluded`() {
        // Sleep interval [0, 60_000) covers minute 0 only. Sample in minute 1 [60_000, 120_000)
        // is adjacent but not overlapping, so it should be counted.
        val input =
            baseInput(
                hrSamples = listOf(sample(60_000L, 130)),
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
                hrSamples = listOf(sample(0L, 130), sample(60_000L, 130)),
                sleepIntervalsMs = listOf(LongInterval(30_000L, 90_000L)),
            )

        val result = EverydayHeartRateLoadCalculator.calculate(input)

        assertEquals(0, result.coverageMinutes)
        assertEquals(0, result.validBucketCount)
        assertEquals(0f, result.nonWorkoutTrimp, 0.001f)
    }
}
