package app.readylytics.health.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyStrainIncreaseTest {
    @Test
    fun `returns null before seven days of data`() {
        val result =
            calculateDailyStrainIncrease(
                dataTenureDays = 6,
                loadSourceMode = LoadSourceMode.WORKOUT_ONLY,
                workoutOnlyGains = listOf(0.09f, 0.09f),
                strainRatioWithDay = 1.5f,
                strainRatioWithoutDay = 1.2f,
            )

        assertNull(result)
    }

    @Test
    fun `sums supplied already-rounded workout-only gains`() {
        val result =
            calculateDailyStrainIncrease(
                dataTenureDays = 7,
                loadSourceMode = LoadSourceMode.WORKOUT_ONLY,
                workoutOnlyGains = listOf(0.09f, 0.09f),
                strainRatioWithDay = null,
                strainRatioWithoutDay = null,
            )

        assertEquals(0.18f, result!!, 0.001f)
    }

    @Test
    fun `returns positive everyday-heart-rate strain-ratio difference`() {
        val result =
            calculateDailyStrainIncrease(
                dataTenureDays = 7,
                loadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
                workoutOnlyGains = emptyList(),
                strainRatioWithDay = 1.5f,
                strainRatioWithoutDay = 1.2f,
            )

        assertEquals(0.3f, result!!, 0.001f)
    }

    @Test
    fun `clamps negative everyday-heart-rate strain-ratio difference to zero`() {
        val result =
            calculateDailyStrainIncrease(
                dataTenureDays = 7,
                loadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
                workoutOnlyGains = emptyList(),
                strainRatioWithDay = 1.2f,
                strainRatioWithoutDay = 1.5f,
            )

        assertEquals(0f, result!!, 0.001f)
    }
}
