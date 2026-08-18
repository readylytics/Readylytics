package app.readylytics.health.domain.scoring.strategies

import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.domain.scoring.components.SleepContinuityCurves
import app.readylytics.health.domain.scoring.sleep.SleepFragmentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp

private const val DELTA = 0.5f

class SleepScoringStrategyTest {
    private val loadStrategy = LoadScoringStrategy()
    private val sleepStrategy = SleepScoringStrategy(loadStrategy)

    @Test
    fun `durationSubScore full TST excellent efficiency scores near ceiling`() {
        val result = sleepStrategy.computeDurationSubScore(durationMinutes = 480, efficiency = 95f, goalSleepHours = 8f)
        val expected =
            0.7f * SleepContinuityCurves.durationTerm(1f, ScoringConstants.Sleep.DEFAULT_HYPERSOMNIA_ONSET_RATIO) +
                0.3f * SleepContinuityCurves.efficiencyTerm(95f)
        assertEquals(expected, result, DELTA)
    }

    @Test
    fun `durationSubScore half TST good efficiency reduced score`() {
        val result = sleepStrategy.computeDurationSubScore(durationMinutes = 240, efficiency = 85f, goalSleepHours = 8f)
        val expected =
            0.7f * SleepContinuityCurves.durationTerm(0.5f, ScoringConstants.Sleep.DEFAULT_HYPERSOMNIA_ONSET_RATIO) +
                0.3f * SleepContinuityCurves.efficiencyTerm(85f)
        assertEquals(expected, result, DELTA)
    }

    @Test
    fun `archSubScore zero duration scores 0`() {
        val result =
            sleepStrategy.computeArchSubScore(
                deepSleepMinutes = 0,
                remSleepMinutes = 0,
                durationMinutes = 0,
                userAge = 30,
                sleepTargets = null,
            )
        assertEquals(0f, result, DELTA)
    }

    @Test
    fun `archSubScore ideal deep REM under 30 scores high`() {
        val durationMinutes = 480
        val deepMinutes = (durationMinutes * 0.20f).toInt() // AgeRange18To29 deep target
        val remMinutes = (durationMinutes * 0.22f).toInt() + 1 // AgeRange18To29 rem target
        val result =
            sleepStrategy.computeArchSubScore(
                deepMinutes,
                remMinutes,
                durationMinutes,
                userAge = 25,
                sleepTargets = null,
            )
        assertEquals(100f, result, DELTA)
    }

    @Test
    fun `sleepScore without fragmentation data uses degraded weights`() {
        val sDur = sleepStrategy.computeDurationSubScore(480, 95f, 8f)
        val sRest = 50f
        val result =
            sleepStrategy.computeSleepScore(
                durationMinutes = 480,
                efficiency = 95f,
                deepSleepMinutes = 86,
                remSleepMinutes = 105,
                goalSleepHours = 8f,
                sRest = sRest,
                userAge = 30,
                stagesSuspicious = false,
                sleepTargets = null,
            )
        val profile = SleepScoreWeightProfile.BALANCED
        val expected = profile.degradedDurationWeight * sDur + profile.degradedRestorationWeight * sRest
        assertEquals(expected, result, DELTA)
    }

    @Test
    fun `restorationSubScore zero Z scores blend HRV RHR`() {
        val result =
            sleepStrategy.computeRestorationSubScore(
                currentHrvMean = 30f,
                muHrvHistory = listOf(30f),
                sigmaHrvHistory = listOf(5f),
                sigmaPrior = 0.18f,
                currentNocturnalRhr = 60f,
                rhrValues = listOf(60),
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
                restorationWeights = null,
                frozenLnMu = null,
                frozenLnSigma = null,
            )
        val hrvScore = 50f
        val rhrScore = 50f
        val expected = 0.5f * hrvScore + 0.5f * rhrScore
        assertEquals(expected, result, DELTA)
    }

    @Test
    fun `sleep score is continuous across the old efficiency boundary`() {
        val fragmentation = SleepFragmentation(wasoMinutes = 15f, awakeningCount = 1)
        val at89 =
            sleepStrategy.computeSleepScore(
                durationMinutes = 450,
                efficiency = 89f,
                deepSleepMinutes = 90,
                remSleepMinutes = 100,
                goalSleepHours = 8f,
                sRest = 70f,
                userAge = 35,
                stagesSuspicious = false,
                sleepTargets = null,
                fragmentation = fragmentation,
            )
        val at90 =
            sleepStrategy.computeSleepScore(
                durationMinutes = 450,
                efficiency = 90f,
                deepSleepMinutes = 90,
                remSleepMinutes = 100,
                goalSleepHours = 8f,
                sRest = 70f,
                userAge = 35,
                stagesSuspicious = false,
                sleepTargets = null,
                fragmentation = fragmentation,
            )

        assertTrue("delta was ${at90 - at89}", abs(at90 - at89) < 0.5f)
    }

    @Test
    fun `hypersomnia scores below hitting the goal`() {
        fun scoreFor(minutes: Int) =
            sleepStrategy.computeSleepScore(
                durationMinutes = minutes,
                efficiency = 92f,
                deepSleepMinutes = 90,
                remSleepMinutes = 100,
                goalSleepHours = 8f,
                sRest = 70f,
                userAge = 35,
                stagesSuspicious = false,
                sleepTargets = null,
                fragmentation = SleepFragmentation(wasoMinutes = 15f, awakeningCount = 1),
            )

        assertTrue(scoreFor(720) < scoreFor(480))
    }

    @Test
    fun `naps within the dead zone are not penalized`() {
        // deepSleepMinutes/remSleepMinutes are chosen so the architecture sub-score is saturated
        // (capped at its target) at both durations — otherwise the same absolute deep/REM minutes
        // become a smaller fraction of a longer total duration and the architecture sub-score would
        // drift with duration, confounding this test's real target: the flat duration-term dead zone.
        fun scoreFor(minutes: Int) =
            sleepStrategy.computeSleepScore(
                durationMinutes = minutes,
                efficiency = 92f,
                deepSleepMinutes = 110,
                remSleepMinutes = 125,
                goalSleepHours = 8f,
                sRest = 70f,
                userAge = 35,
                stagesSuspicious = false,
                sleepTargets = null,
                fragmentation = SleepFragmentation(wasoMinutes = 15f, awakeningCount = 1),
            )

        assertEquals(scoreFor(480), scoreFor(570), 0.01f)
    }

    @Test
    fun `suspicious stages drop architecture and fragmentation and renormalize`() {
        val score =
            sleepStrategy.computeSleepScore(
                durationMinutes = 480,
                efficiency = 92f,
                deepSleepMinutes = 0,
                remSleepMinutes = 0,
                goalSleepHours = 8f,
                sRest = 60f,
                userAge = 35,
                stagesSuspicious = true,
                sleepTargets = null,
                fragmentation = null,
            )

        val expectedDuration = sleepStrategy.computeDurationSubScore(480, 92f, 8f)
        val profile = SleepScoreWeightProfile.BALANCED
        val expected =
            profile.degradedDurationWeight * expectedDuration + profile.degradedRestorationWeight * 60f

        assertEquals(expected, score, 0.01f)
    }

    @Test
    fun `poor regularity applies at most an eight percent penalty`() {
        fun scoreFor(regularity: Float?) =
            sleepStrategy.computeSleepScore(
                durationMinutes = 480,
                efficiency = 92f,
                deepSleepMinutes = 90,
                remSleepMinutes = 100,
                goalSleepHours = 8f,
                sRest = 70f,
                userAge = 35,
                stagesSuspicious = false,
                sleepTargets = null,
                fragmentation = SleepFragmentation(wasoMinutes = 15f, awakeningCount = 1),
                regularityScore = regularity,
            )

        assertEquals(scoreFor(null), scoreFor(100f), 0.01f)
        assertEquals(scoreFor(null) * 0.92f, scoreFor(0f), 0.01f)
    }

    @Test
    fun `light sleeper profile punishes fragmentation harder than hours first`() {
        fun scoreFor(profile: SleepScoreWeightProfile) =
            sleepStrategy.computeSleepScore(
                durationMinutes = 480,
                efficiency = 92f,
                deepSleepMinutes = 90,
                remSleepMinutes = 100,
                goalSleepHours = 8f,
                sRest = 70f,
                userAge = 35,
                stagesSuspicious = false,
                sleepTargets = null,
                fragmentation = SleepFragmentation(wasoMinutes = 90f, awakeningCount = 8),
                weightProfile = profile,
            )

        assertTrue(scoreFor(SleepScoreWeightProfile.LIGHT_SLEEPER) < scoreFor(SleepScoreWeightProfile.HOURS_FIRST))
    }
}

class RasScoringStrategyTest {
    private val rasStrategy = RasScoringStrategy()

    @Test
    fun `strainRatio zero CTL returns 0`() {
        val result = rasStrategy.computeStrainRatio(atl = 50f, ctl = 0f)
        assertEquals(0f, result, DELTA)
    }

    @Test
    fun `ctlEma fewer than MIN_SESSIONS returns seed`() {
        val data = listOf(50f, 55f)
        val result = rasStrategy.computeCtlEma(data, seedFitnessLevel = 40f, windowDays = 42)
        assertEquals(40f, result, DELTA)
    }

    @Test
    fun `atlEma exact session count returns SMA`() {
        val minSessions = ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
        val data = List(minSessions) { 50f }
        val result = rasStrategy.computeAtlEma(data, seedFatigueLevel = 40f, windowDays = 7)
        assertEquals(50f, result, DELTA)
    }

    @Test
    fun `emaWithDecay empty map returns DEFAULT_FITNESS_LEVEL`() {
        val result = rasStrategy.computeCtlEmaWithDecay(emptyMap(), LocalDate.now(), windowDays = 42)
        assertEquals(ScoringConstants.DEFAULT_FITNESS_LEVEL, result, DELTA)
    }

    @Test
    fun `emaWithDecay single day returns TRIMP value`() {
        val today = LocalDate.now()
        val data = mapOf(today to 50f)
        val result = rasStrategy.computeCtlEmaWithDecay(data, today, windowDays = 42)
        assertEquals(50f, result, DELTA)
    }
}

class LoadScoringStrategyTest {
    private val loadStrategy = LoadScoringStrategy()

    @Test
    fun `loadScore SR below sweet spot returns 100`() {
        val result = loadStrategy.computeLoadScore(sr = 1.0f)
        assertEquals(100f, result, DELTA)
    }

    @Test
    fun `loadScore SR above sweet spot decays quadratically`() {
        val sr = 2.0f
        val excess = sr - ScoringConstants.Strain.SR_SWEET_SPOT_MAX
        val expected = (100f * exp(-ScoringConstants.Strain.QUADRATIC_PENALTY_K * excess * excess)).coerceIn(0f, 100f)
        val result = loadStrategy.computeLoadScore(sr)
        assertEquals(expected, result, DELTA)
    }

    @Test
    fun `hrvScore zero Z returns 50`() {
        val result = loadStrategy.computeHrvScore(z = 0f)
        assertEquals(50f, result, DELTA)
    }

    @Test
    fun `hrvZScore empty history returns null`() {
        val result =
            loadStrategy.computeHrvZScore(
                currentRmssdMs = 50f,
                muHistory = emptyList(),
                sigmaHistory = emptyList(),
                sigmaPrior = 0.18f,
                baselineOverride = null,
            )
        assertNull(result)
    }

    @Test
    fun `recoveryFlags calibrating sets flag`() {
        val result =
            loadStrategy.computeRecoveryFlags(
                zLnHrv = 0f,
                zRhr = 0f,
                rhrDeltaBpm = null,
                yesterdayZLnHrv = null,
                yesterdayZRhr = null,
                hrvMissing = false,
                stagesSuspicious = false,
                isLateNadir = false,
                isCalibrating = true,
                emergencyFlags = null,
            )
        assertEquals(setOf(RecoveryFlag.CALIBRATING), result)
    }

    @Test
    fun `readinessScore illness flag caps at 65`() {
        val result =
            loadStrategy.computeReadinessScore(
                sRest = 100f,
                sleepScore = 100f,
                loadScore = 100f,
                recoveryFlags = setOf(RecoveryFlag.ILLNESS_ONSET),
            )
        assertEquals(ScoringConstants.Readiness.ILLNESS_MAX_SCORE, result, DELTA)
    }

    @Test
    fun `rhrZScore uses frozenSigma when provided`() {
        val result =
            loadStrategy.computeRhrZScore(
                currentRhrBpm = 65f,
                rhrHistory = listOf(60, 62, 58),
                baselineOverride = 60f,
                frozenSigma = 2.0f,
            )
        assertEquals(2.5f, result!!, 0.001f)
    }

    @Test
    fun `rhrZScore uses fallback when frozenSigma is null and rhrHistory is empty`() {
        val result =
            loadStrategy.computeRhrZScore(
                currentRhrBpm = 65f,
                rhrHistory = emptyList(),
                baselineOverride = 60f,
                frozenSigma = null,
            )
        assertEquals(1.66667f, result!!, 0.001f)
    }

    @Test
    fun `rhrZScore uses fallback when frozenSigma is null and rhrHistory size is 1`() {
        val result =
            loadStrategy.computeRhrZScore(
                currentRhrBpm = 65f,
                rhrHistory = listOf(60),
                baselineOverride = null,
                frozenSigma = null,
            )
        assertEquals(1.66667f, result!!, 0.001f)
    }
}
