package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidNightTest {
    private val calculator = LoadScoringStrategy()

    private fun validate(
        rmssdMs: Float? = 50f,
        rhrBpm: Float? = 55f,
        durationMinutes: Int = 480,
        deepMinutes: Int = 80,
        remMinutes: Int = 96,
    ) = calculator.validateNight(rmssdMs, rhrBpm, durationMinutes, deepMinutes, remMinutes)

    // ─── RMSSD bounds ─────────────────────────────────────────────────────────
    @Test
    fun `rmssd 50ms is valid`() = assertTrue(validate(rmssdMs = 50f).rmssdValid)

    @Test
    fun `rmssd at lower bound 5ms is valid`() = assertTrue(validate(rmssdMs = 5f).rmssdValid)

    @Test
    fun `rmssd at upper bound 250ms is valid`() = assertTrue(validate(rmssdMs = 250f).rmssdValid)

    @Test
    fun `rmssd below lower bound 4_9ms is invalid`() = assertFalse(validate(rmssdMs = 4.9f).rmssdValid)

    @Test
    fun `rmssd above upper bound 250_1ms is invalid`() = assertFalse(validate(rmssdMs = 250.1f).rmssdValid)

    @Test
    fun `null rmssd is invalid`() = assertFalse(validate(rmssdMs = null).rmssdValid)

    // ─── RHR bounds ───────────────────────────────────────────────────────────
    @Test
    fun `rhr 60 is valid`() = assertTrue(validate(rhrBpm = 60f).rhrValid)

    @Test
    fun `rhr at lower bound 30 is valid`() = assertTrue(validate(rhrBpm = 30f).rhrValid)

    @Test
    fun `rhr at upper bound 100 is valid`() = assertTrue(validate(rhrBpm = 100f).rhrValid)

    @Test
    fun `rhr below 30 is invalid`() = assertFalse(validate(rhrBpm = 29.9f).rhrValid)

    @Test
    fun `rhr above 100 is invalid`() = assertFalse(validate(rhrBpm = 100.1f).rhrValid)

    @Test
    fun `null rhr is valid (treated as missing, not invalid)`() = assertTrue(validate(rhrBpm = null).rhrValid)

    // ─── Duration ─────────────────────────────────────────────────────────────
    @Test
    fun `duration 480 min is valid`() = assertTrue(validate(durationMinutes = 480).durationValid)

    @Test
    fun `duration at floor 240 min is valid`() = assertTrue(validate(durationMinutes = 240).durationValid)

    @Test
    fun `duration below floor 239 min is invalid`() = assertFalse(validate(durationMinutes = 239).durationValid)

    @Test
    fun `duration zero is invalid`() = assertFalse(validate(durationMinutes = 0).durationValid)

    // ─── Stage bounds ─────────────────────────────────────────────────────────
    @Test
    fun `plausible stages are valid and not suspicious`() {
        val result = validate(deepMinutes = 80, remMinutes = 96, durationMinutes = 480) // 16.7% deep, 20% REM
        assertTrue(result.stagesValid)
        assertFalse(result.stagesSuspicious)
    }

    @Test
    fun `deep over 40 percent is invalid`() {
        // 200 / 480 = 41.7% → invalid
        val result = validate(deepMinutes = 200, remMinutes = 80, durationMinutes = 480)
        assertFalse(result.stagesValid)
    }

    @Test
    fun `rem over 45 percent is invalid`() {
        // 220 / 480 = 45.8% → invalid
        val result = validate(deepMinutes = 60, remMinutes = 220, durationMinutes = 480)
        assertFalse(result.stagesValid)
    }

    @Test
    fun `deep + rem over 70 percent is suspicious but not invalid`() {
        // deep=38%, rem=33% → combined=71% → suspicious; each under individual limit
        val result = validate(deepMinutes = 182, remMinutes = 160, durationMinutes = 480)
        assertTrue(result.stagesSuspicious)
    }

    // ─── canContributeToBaseline ───────────────────────────────────────────────
    @Test
    fun `canContributeToBaseline is false when rmssd invalid`() {
        assertFalse(validate(rmssdMs = 4f).canContributeToBaseline)
    }

    @Test
    fun `canContributeToBaseline is false when duration too short`() {
        assertFalse(validate(durationMinutes = 180).canContributeToBaseline)
    }

    @Test
    fun `canContributeToBaseline is false when rhr invalid`() {
        assertFalse(validate(rhrBpm = 25f).canContributeToBaseline)
    }

    @Test
    fun `canContributeToBaseline is true for a good night`() {
        assertTrue(validate().canContributeToBaseline)
    }
}
