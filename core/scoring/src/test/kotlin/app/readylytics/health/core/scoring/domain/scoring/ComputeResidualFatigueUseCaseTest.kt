package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ComputeResidualFatigueUseCaseTest {

    private val useCase = ComputeResidualFatigueUseCase()
    private val defaultConfig = ResidualFatigueConfig()

    private fun workout(endTimeMs: Long, trimp: Float) =
        ComputeResidualFatigueUseCase.FatigueWorkoutInput(endTimeMs, trimp)

    @Test
    fun `single workout - fatigue equals gain times trimp at workout end`() {
        val w = workout(endTimeMs = 1000L, trimp = 100f)
        val result = useCase.compute(evaluationTimeMs = 1000L, workouts = listOf(w), config = defaultConfig)
        assertEquals(100f, result, 0.01f)
    }

    @Test
    fun `single workout - fatigue halves after one half-life`() {
        val endMs = 0L
        val evalMs = (24 * 3_600_000).toLong()
        val w = workout(endTimeMs = endMs, trimp = 100f)
        val result = useCase.compute(evalMs, listOf(w), defaultConfig)
        assertEquals(50f, result, 0.01f)
    }

    @Test
    fun `multiple workouts stack additively`() {
        val w1 = workout(endTimeMs = 0L, trimp = 80f)
        val w2 = workout(endTimeMs = (12 * 3_600_000).toLong(), trimp = 60f)
        val evalMs = (24 * 3_600_000).toLong()
        val result = useCase.compute(evalMs, listOf(w1, w2), defaultConfig)
        val expected = (80.0 * 2.0.pow(-24.0 / 24.0) + 60.0 * 2.0.pow(-12.0 / 24.0)).toFloat()
        assertEquals(expected, result, 0.01f)
    }

    @Test
    fun `rest day - no new impulse, fatigue decays`() {
        val w = workout(endTimeMs = 0L, trimp = 100f)
        val evalMs = (48 * 3_600_000).toLong()
        val result = useCase.compute(evalMs, listOf(w), defaultConfig)
        assertEquals(25f, result, 0.01f)
    }

    @Test
    fun `same TRIMP at 06h vs 21h - different next-morning fatigue`() {
        val morningEnd = (6 * 3_600_000).toLong()
        val eveningEnd = (21 * 3_600_000).toLong()
        val nextMorningEval = (30 * 3_600_000).toLong()
        val morningResult = useCase.compute(nextMorningEval, listOf(workout(morningEnd, 100f)), defaultConfig)
        val eveningResult = useCase.compute(nextMorningEval, listOf(workout(eveningEnd, 100f)), defaultConfig)
        assertTrue(eveningResult > morningResult)
    }

    @Test
    fun `workout crossing midnight - endTime determines timing`() {
        val endMs = (25 * 3_600_000).toLong()
        val evalMs = (30 * 3_600_000).toLong()
        val w = workout(endMs, 80f)
        val result = useCase.compute(evalMs, listOf(w), defaultConfig)
        val expected = (80.0 * 2.0.pow(-5.0 / 24.0)).toFloat()
        assertEquals(expected, result, 0.01f)
    }

    @Test
    fun `zero TRIMP contributes nothing`() {
        val w = workout(endTimeMs = 0L, trimp = 0f)
        val result = useCase.compute(1000L, listOf(w), defaultConfig)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `empty workout list returns zero`() {
        val result = useCase.compute(1000L, emptyList(), defaultConfig)
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `future workout contributes nothing to summation or accumulator`() {
        val evalMs = (10 * 3_600_000).toLong()
        val futureEnd = evalMs + 3_600_000L
        val future = workout(futureEnd, 100f)

        val summation = useCase.compute(evalMs, listOf(future), defaultConfig)
        assertEquals(0f, summation, 0.001f)

        val (acc, _) =
            useCase.advanceAccumulator(
                accumulatedFatigue = 50.0,
                lastEvalMs = 0L,
                currentEvalMs = evalMs,
                newImpulses = listOf(future),
                config = defaultConfig,
            )
        val expected = 50.0 * 2.0.pow(-10.0 / 24.0)
        assertEquals(expected, acc, 0.001)
    }

    @Test
    fun `custom gain scales output proportionally`() {
        val w = workout(endTimeMs = 1000L, trimp = 100f)
        val gain2 = useCase.compute(1000L, listOf(w), defaultConfig.copy(fatigueGain = 2.0f))
        val gain1 = useCase.compute(1000L, listOf(w), defaultConfig)
        assertEquals(gain1 * 2f, gain2, 0.01f)
    }

    @Test
    fun `custom half-life changes decay rate`() {
        val w = workout(endTimeMs = 0L, trimp = 100f)
        val evalMs = (12 * 3_600_000).toLong()
        val result = useCase.compute(evalMs, listOf(w), defaultConfig.copy(halfLifeHours = 12f))
        assertEquals(50f, result, 0.01f)
    }

    @Test
    fun `out-of-order advance does not amplify accumulated fatigue`() {
        // An advance with currentEvalMs before lastEvalMs would otherwise compute a negative
        // elapsed and invert the decay into amplification; the clamp must keep fatigue flat.
        val lastEvalMs = (24 * 3_600_000).toLong()
        val outOfOrderEvalMs = (12 * 3_600_000).toLong()
        val (acc, _) =
            useCase.advanceAccumulator(
                accumulatedFatigue = 100.0,
                lastEvalMs = lastEvalMs,
                currentEvalMs = outOfOrderEvalMs,
                newImpulses = emptyList(),
                config = defaultConfig,
            )
        assertEquals(100.0, acc, 0.001)
    }

    @Test
    fun `snapshot is evaluated at day end, so the current day is a forward projection`() {
        // ResidualFatigueComputer always evaluates at context.nextDayMidnightMs. For the current
        // day that point is in the future, so the stored value is a projection to the end of today,
        // not fatigue at the moment of the sync. That is deliberate: it keeps the series
        // deterministic and wall-clock independent. Nothing may derive "fatigue now" from it.
        val dayStartMs = 0L
        val nextDayMidnightMs = (24 * 3_600_000).toLong()
        val workoutEndMs = (17 * 3_600_000).toLong()
        val workouts = listOf(workout(workoutEndMs, 100f))

        val snapshot = useCase.compute(nextDayMidnightMs, workouts, defaultConfig)
        val expected = (100.0 * 2.0.pow(-7.0 / 24.0)).toFloat()
        assertEquals(expected, snapshot, 0.01f)

        // Re-running the same day later in the day yields the identical value: the evaluation point
        // is the day boundary, never the current wall clock.
        val recomputedLaterInTheDay = useCase.compute(nextDayMidnightMs, workouts, defaultConfig)
        assertEquals(snapshot, recomputedLaterInTheDay, 0f)

        // And the snapshot is strictly lower than fatigue at the moment the workout ended, which is
        // what a naive "fatigue now" reading of the stored value would assume it to be.
        val atWorkoutEnd = useCase.compute(workoutEndMs, workouts, defaultConfig)
        assertTrue(snapshot < atWorkoutEnd)
        assertTrue(useCase.compute(dayStartMs, workouts, defaultConfig) < snapshot)
    }

    @Test
    fun `non-positive half-life yields a finite zero instead of NaN`() {
        // halfLifeMs == 0 makes -elapsed / halfLifeMs either NaN (elapsed 0) or -Infinity, and the
        // resulting NaN would be persisted into daily_summaries and survive into the backup JSON.
        val w = workout(endTimeMs = 0L, trimp = 100f)
        for (halfLifeHours in listOf(0f, -24f)) {
            val config = defaultConfig.copy(halfLifeHours = halfLifeHours)

            val atImpulse = useCase.compute(0L, listOf(w), config)
            assertFalse(atImpulse.isNaN())
            assertEquals(0f, atImpulse, 0.001f)

            val later = useCase.compute((24 * 3_600_000).toLong(), listOf(w), config)
            assertFalse(later.isNaN())
            assertEquals(0f, later, 0.001f)

            val (acc, advancedEvalMs) =
                useCase.advanceAccumulator(
                    accumulatedFatigue = 100.0,
                    lastEvalMs = 0L,
                    currentEvalMs = (24 * 3_600_000).toLong(),
                    newImpulses = listOf(w),
                    config = config,
                )
            assertFalse(acc.isNaN())
            assertEquals(0.0, acc, 0.001)
            assertEquals((24 * 3_600_000).toLong(), advancedEvalMs)
        }
    }

    @Test
    fun `non-finite parameters are rejected at construction`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                ResidualFatigueConfig(halfLifeHours = bad)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ResidualFatigueConfig(fatigueGain = bad)
            }
        }
    }

    @Test
    fun `config defaults match the shipped settings defaults`() {
        assertEquals(SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS, defaultConfig.halfLifeHours, 0f)
        assertEquals(SettingsDefaults.RESIDUAL_FATIGUE_GAIN, defaultConfig.fatigueGain, 0f)
    }

    @Test
    fun `clamped coerces out-of-range stored preferences into the validated bounds`() {
        val tooLow =
            ResidualFatigueConfig.clamped(halfLifeHours = 0f, fatigueGain = 0.01f)
        assertEquals(SettingsDefaults.MIN_RESIDUAL_FATIGUE_HALF_LIFE_HOURS, tooLow.halfLifeHours, 0f)
        assertEquals(SettingsDefaults.MIN_RESIDUAL_FATIGUE_GAIN, tooLow.fatigueGain, 0f)

        val tooHigh =
            ResidualFatigueConfig.clamped(halfLifeHours = 500f, fatigueGain = 10f)
        assertEquals(SettingsDefaults.MAX_RESIDUAL_FATIGUE_HALF_LIFE_HOURS, tooHigh.halfLifeHours, 0f)
        assertEquals(SettingsDefaults.MAX_RESIDUAL_FATIGUE_GAIN, tooHigh.fatigueGain, 0f)

        val inRange =
            ResidualFatigueConfig.clamped(halfLifeHours = 36f, fatigueGain = 2.5f)
        assertEquals(36f, inRange.halfLifeHours, 0f)
        assertEquals(2.5f, inRange.fatigueGain, 0f)
    }

    @Test
    fun `clamped falls back to the defaults for non-finite stored preferences`() {
        val config =
            ResidualFatigueConfig.clamped(
                halfLifeHours = Float.NaN,
                fatigueGain = Float.POSITIVE_INFINITY,
            )
        assertEquals(SettingsDefaults.RESIDUAL_FATIGUE_HALF_LIFE_HOURS, config.halfLifeHours, 0f)
        assertEquals(SettingsDefaults.RESIDUAL_FATIGUE_GAIN, config.fatigueGain, 0f)
    }

    @Test
    fun `accumulator produces identical results to summation`() {
        val workouts = listOf(
            workout(endTimeMs = 0L, trimp = 100f),
            workout(endTimeMs = (8 * 3_600_000).toLong(), trimp = 60f),
            workout(endTimeMs = (20 * 3_600_000).toLong(), trimp = 80f),
        )
        val evalMs = (36 * 3_600_000).toLong()
        val summationResult = useCase.compute(evalMs, workouts, defaultConfig)

        var accFatigue = 0.0
        var lastEvalMs = Long.MIN_VALUE
        for (w in workouts) {
            val (newFatigue, newLastEval) = useCase.advanceAccumulator(
                accumulatedFatigue = accFatigue,
                lastEvalMs = lastEvalMs,
                currentEvalMs = w.endTimeMs,
                newImpulses = listOf(w),
                config = defaultConfig,
            )
            accFatigue = newFatigue
            lastEvalMs = newLastEval
        }
        val (finalFatigue, _) = useCase.advanceAccumulator(accFatigue, lastEvalMs, evalMs, emptyList(), defaultConfig)

        assertEquals(summationResult, finalFatigue.toFloat(), 0.01f)
    }
}
