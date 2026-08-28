package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import org.junit.Assert.assertEquals
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
        assert(eveningResult > morningResult)
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
    fun `disabled config returns zero`() {
        val w = workout(endTimeMs = 0L, trimp = 100f)
        val result = useCase.compute(1000L, listOf(w), defaultConfig.copy(enabled = false))
        assertEquals(0f, result, 0.001f)
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
