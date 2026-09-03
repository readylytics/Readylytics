package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeTrainingReadinessUseCaseTest {
    private val useCase =
        ComputeTrainingReadinessUseCase(
            CompositeScoringCalculator(
                sleepStrategy = SleepScoringStrategy(LoadScoringStrategy()),
                rasStrategy = RasScoringStrategy(),
                loadStrategy = LoadScoringStrategy(),
            ),
        )

    @Test
    fun `zero fatigue produces 100 acute recovery`() {
        val result = useCase.compute(80f, 70f, 60f, 72f, 0f, emptySet(), config(scale = 100f, weight = .9f))

        assertEquals(100f, result.acuteLoadRecovery!!, 0f)
    }

    @Test
    fun `weight one preserves legacy readiness exactly`() {
        val result = useCase.compute(81f, 72f, 61f, 74.1f, 150f, emptySet(), config(weight = 1f))

        assertEquals(61f, result.trainingLoadReadiness!!, 0f)
        assertEquals(74.1f, result.trainingReadiness!!, 0f)
    }

    @Test
    fun `unavailable fatigue never becomes perfect recovery`() {
        val result = useCase.compute(80f, 70f, 60f, 71f, null, emptySet(), config())

        assertNull(result.acuteLoadRecovery)
        assertEquals(60f, result.trainingLoadReadiness!!, 0f)
        assertEquals(71f, result.trainingReadiness!!, 0f)
    }

    @Test
    fun `higher fatigue lowers acute load recovery`() {
        val lowerFatigue = useCase.compute(80f, 70f, 60f, 72f, 25f, emptySet(), config())
        val higherFatigue = useCase.compute(80f, 70f, 60f, 72f, 100f, emptySet(), config())

        assertTrue(higherFatigue.acuteLoadRecovery!! < lowerFatigue.acuteLoadRecovery!!)
    }

    @Test
    fun `higher fatigue scale raises acute load recovery at fixed fatigue`() {
        val smallerScale = useCase.compute(80f, 70f, 60f, 72f, 100f, emptySet(), config(scale = 75f))
        val largerScale = useCase.compute(80f, 70f, 60f, 72f, 100f, emptySet(), config(scale = 175f))

        assertTrue(largerScale.acuteLoadRecovery!! > smallerScale.acuteLoadRecovery!!)
    }

    @Test
    fun `load balance weight uses the configured load acute blend`() {
        val result = useCase.compute(80f, 70f, 20f, 72f, 35.667496f, emptySet(), config(scale = 100f, weight = .9f))

        assertEquals(70f, result.acuteLoadRecovery!!, 0.001f)
        assertEquals(25f, result.trainingLoadReadiness!!, 0.001f)
    }

    @Test
    fun `higher fatigue cannot increase training readiness when valid inputs are fixed`() {
        val lowerFatigue = useCase.compute(100f, 100f, 20f, 72f, 0f, emptySet(), config())
        val higherFatigue = useCase.compute(100f, 100f, 20f, 72f, 100f, emptySet(), config())

        assertTrue(higherFatigue.trainingReadiness!! <= lowerFatigue.trainingReadiness!!)
    }

    @Test
    fun `scoring calculator illness cap applies to training readiness`() {
        val result =
            useCase.compute(
                restoration = 100f,
                sleepScore = 100f,
                loadScore = 100f,
                legacyReadiness = 100f,
                residualFatigue = 0f,
                recoveryFlags = setOf(RecoveryFlag.ILLNESS_ONSET),
                config = config(),
            )

        assertEquals(ScoringConstants.Readiness.ILLNESS_MAX_SCORE, result.trainingReadiness!!, 0f)
    }

    @Test
    fun `acute blended and final scores remain within display bounds`() {
        val result = useCase.compute(100f, 100f, 100f, 100f, -100f, emptySet(), config())

        assertTrue(result.acuteLoadRecovery!! in 0f..100f)
        assertTrue(result.trainingLoadReadiness!! in 0f..100f)
        assertTrue(result.trainingReadiness!! in 0f..100f)
    }

    @Test
    fun `corrupt stored config cannot produce non-finite or out-of-domain projections`() {
        val corruptStoredConfig = TrainingReadinessConfig.fromStored(Float.NaN, Float.POSITIVE_INFINITY)
        val result = useCase.compute(100f, 100f, 100f, 100f, 100f, emptySet(), corruptStoredConfig)

        assertTrue(result.acuteLoadRecovery!!.isFinite() && result.acuteLoadRecovery in 0f..100f)
        assertTrue(result.trainingLoadReadiness!!.isFinite() && result.trainingLoadReadiness in 0f..100f)
        assertTrue(result.trainingReadiness!!.isFinite() && result.trainingReadiness in 0f..100f)
    }

    private fun config(
        scale: Float = 100f,
        weight: Float = .9f,
    ): TrainingReadinessConfig = TrainingReadinessConfig.fromStored(scale, weight)
}
