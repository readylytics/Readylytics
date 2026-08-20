package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepFragmentationCalculator

import app.readylytics.health.core.model.domain.repository.SleepStageData
import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.01f
private const val MINUTE = 60_000L

class SleepFragmentationCalculatorTest {
    private fun stage(
        type: String,
        startMinute: Long,
        endMinute: Long,
    ) = SleepStageData(
        stageType = type,
        startTime = startMinute * MINUTE,
        endTime = endMinute * MINUTE,
        durationMinutes = (endMinute - startMinute).toInt(),
    )

    @Test
    fun `wake before onset and after final awakening is excluded`() {
        val stages =
            listOf(
                stage("AWAKE", 0, 10),
                stage("LIGHT", 10, 60),
                stage("AWAKE", 60, 75),
                stage("DEEP", 75, 120),
                stage("AWAKE", 120, 140),
            )

        val result = SleepFragmentationCalculator.compute(stages)

        assertEquals(15f, result.wasoMinutes, DELTA)
        assertEquals(1, result.awakeningCount)
    }

    @Test
    fun `awakenings shorter than 90 seconds count toward WASO but not the count`() {
        val stages =
            listOf(
                stage("LIGHT", 0, 60),
                SleepStageData("AWAKE", 60 * MINUTE, 60 * MINUTE + 89_999L, 1),
                stage("LIGHT", 62, 120),
                SleepStageData("AWAKE", 120 * MINUTE, 120 * MINUTE + 90_000L, 2),
                stage("LIGHT", 123, 180),
            )

        val result = SleepFragmentationCalculator.compute(stages)

        assertEquals(1, result.awakeningCount)
        assertEquals((89_999L + 90_000L) / 60_000f, result.wasoMinutes, DELTA)
    }

    @Test
    fun `unsorted and overlapping awake segments are merged once`() {
        val stages =
            listOf(
                stage("AWAKE", 70, 90),
                stage("LIGHT", 0, 60),
                stage("AWAKE", 60, 80),
                stage("LIGHT", 90, 150),
            )

        val result = SleepFragmentationCalculator.compute(stages)

        assertEquals(30f, result.wasoMinutes, DELTA)
        assertEquals(1, result.awakeningCount)
    }

    @Test
    fun `session with no sleep stages yields none`() {
        val result = SleepFragmentationCalculator.compute(listOf(stage("AWAKE", 0, 60)))

        assertEquals(0f, result.wasoMinutes, DELTA)
        assertEquals(0, result.awakeningCount)
    }

    @Test
    fun `empty stage list yields none`() {
        val result = SleepFragmentationCalculator.compute(emptyList())

        assertEquals(0f, result.wasoMinutes, DELTA)
        assertEquals(0, result.awakeningCount)
    }
}
