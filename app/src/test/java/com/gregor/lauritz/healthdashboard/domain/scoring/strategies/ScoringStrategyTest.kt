package com.gregor.lauritz.healthdashboard.domain.scoring.strategies

import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp

private const val DELTA = 0.5f

class SleepScoringStrategyTest {
    private val loadStrategy = LoadScoringStrategy()
    private val sleepStrategy = SleepScoringStrategy(loadStrategy)

    @Test
    fun `durationSubScore full TST excellent efficiency scores 100`() {
        val result = sleepStrategy.computeDurationSubScore(durationMinutes = 480, efficiency = 95f, goalSleepHours = 8f)
        assertEquals(100f, result, DELTA)
    }

    @Test
    fun `durationSubScore half TST good efficiency reduced score`() {
        val result = sleepStrategy.computeDurationSubScore(durationMinutes = 240, efficiency = 85f, goalSleepHours = 8f)
        val expected = 0.7f * 50f + 0.3f * 85f
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
    fun `sleepScore suspicious stages arch weight dropped`() {
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
                stagesSuspicious = true,
                sleepTargets = null,
            )
        val expected = 0.75f * sDur + 0.0f + 0.25f * sRest
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
}

class PaiScoringStrategyTest {
    private val paiStrategy = PaiScoringStrategy()

    @Test
    fun `strainRatio zero CTL returns 0`() {
        val result = paiStrategy.computeStrainRatio(atl = 50f, ctl = 0f)
        assertEquals(0f, result, DELTA)
    }

    @Test
    fun `ctlEma fewer than MIN_SESSIONS returns seed`() {
        val data = listOf(50f, 55f)
        val result = paiStrategy.computeCtlEma(data, seedFitnessLevel = 40f, windowDays = 42)
        assertEquals(40f, result, DELTA)
    }

    @Test
    fun `atlEma exact session count returns SMA`() {
        val minSessions = ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION
        val data = List(minSessions) { 50f }
        val result = paiStrategy.computeAtlEma(data, seedFatigueLevel = 40f, windowDays = 7)
        assertEquals(50f, result, DELTA)
    }

    @Test
    fun `emaWithDecay empty map returns DEFAULT_FITNESS_LEVEL`() {
        val result = paiStrategy.computeCtlEmaWithDecay(emptyMap(), LocalDate.now(), windowDays = 42)
        assertEquals(ScoringConstants.DEFAULT_FITNESS_LEVEL, result, DELTA)
    }

    @Test
    fun `emaWithDecay single day returns TRIMP value`() {
        val today = LocalDate.now()
        val data = mapOf(today to 50f)
        val result = paiStrategy.computeCtlEmaWithDecay(data, today, windowDays = 42)
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
}
