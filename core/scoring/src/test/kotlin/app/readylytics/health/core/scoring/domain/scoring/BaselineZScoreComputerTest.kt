package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class BaselineZScoreComputerTest {
    private val calculator =
        CompositeScoringCalculator(
            SleepScoringStrategy(LoadScoringStrategy()),
            RasScoringStrategy(),
            LoadScoringStrategy(),
        )

    private val computer = BaselineZScoreComputer(calculator)

    @Test
    fun `computeZScores with normal values produces expected scores`() {
        val hrvHistory = List(14) { 50f }
        val rhrHistory = List(14) { 60 }

        val result =
            computer.computeZScores(
                hrvParams =
                    BaselineZScoreComputer.HrvZScoreParams(
                        sessionHrvSamples = listOf(50f, 52f, 48f),
                        currentHrvMean = 50f,
                        muHrvHistory = hrvHistory,
                        effectiveSigmaHistory = hrvHistory,
                        sigmaPrior = 0.25f,
                    ),
                rhrParams =
                    BaselineZScoreComputer.RhrZScoreParams(
                        currentNocturnalRhr = 60,
                        rhrValues = rhrHistory,
                        baselineRhrValue = 60,
                    ),
            )

        assertNotNull(result.zHrv)
        assertEquals(0f, result.zHrv!!, 0.1f)
        assertNotNull(result.zRhr)
        assertEquals(0f, result.zRhr!!, 0.1f)
        assertEquals(0f, result.rhrDeltaBpm!!, 0.01f)
    }

    @Test
    fun `computeZScores with elevated HRV and lowered RHR produces positive and negative z-scores`() {
        val hrvHistory = List(14) { 50f }
        val rhrHistory = (55..65).toList()

        val result =
            computer.computeZScores(
                hrvParams =
                    BaselineZScoreComputer.HrvZScoreParams(
                        sessionHrvSamples = listOf(70f, 75f),
                        currentHrvMean = 70f,
                        muHrvHistory = hrvHistory,
                        effectiveSigmaHistory = hrvHistory,
                        sigmaPrior = 0.25f,
                    ),
                rhrParams =
                    BaselineZScoreComputer.RhrZScoreParams(
                        currentNocturnalRhr = 50,
                        rhrValues = rhrHistory,
                        baselineRhrValue = 60,
                    ),
            )

        assertNotNull(result.zHrv)
        assert(result.zHrv!! > 0f)
        assertNotNull(result.zRhr)
        assert(result.zRhr!! < 0f)
        assertEquals(-10f, result.rhrDeltaBpm!!, 0.01f)
    }

    @Test
    fun `computeZScores with empty HRV samples returns null zHrv`() {
        val result =
            computer.computeZScores(
                hrvParams =
                    BaselineZScoreComputer.HrvZScoreParams(
                        sessionHrvSamples = emptyList(),
                        currentHrvMean = 0f,
                        muHrvHistory = emptyList(),
                        effectiveSigmaHistory = emptyList(),
                        sigmaPrior = 0.25f,
                    ),
                rhrParams =
                    BaselineZScoreComputer.RhrZScoreParams(
                        currentNocturnalRhr = 62,
                        rhrValues = listOf(60, 62, 64),
                        baselineRhrValue = 60,
                    ),
            )

        assertNull(result.zHrv)
        assertNotNull(result.zRhr)
        assertEquals(2f, result.rhrDeltaBpm!!, 0.01f)
    }

    @Test
    fun `computeZScores with null nocturnal RHR returns null zRhr and delta`() {
        val result =
            computer.computeZScores(
                hrvParams =
                    BaselineZScoreComputer.HrvZScoreParams(
                        sessionHrvSamples = listOf(50f),
                        currentHrvMean = 50f,
                        muHrvHistory = listOf(50f),
                        effectiveSigmaHistory = listOf(50f),
                        sigmaPrior = 0.25f,
                    ),
                rhrParams =
                    BaselineZScoreComputer.RhrZScoreParams(
                        currentNocturnalRhr = null,
                        rhrValues = emptyList(),
                    ),
            )

        assertNotNull(result.zHrv)
        assertNull(result.zRhr)
        assertNull(result.rhrDeltaBpm)
    }

    @Test
    fun `computeZScores with frozen baselines respects frozen mu and sigma`() {
        val frozenLnMu = ln(60f)
        val frozenLnSigma = 0.2f

        val result =
            computer.computeZScores(
                hrvParams =
                    BaselineZScoreComputer.HrvZScoreParams(
                        sessionHrvSamples = listOf(60f),
                        currentHrvMean = 60f,
                        muHrvHistory = emptyList(),
                        effectiveSigmaHistory = emptyList(),
                        sigmaPrior = 0.25f,
                        frozenHrvMu = frozenLnMu,
                        frozenHrvSigma = frozenLnSigma,
                    ),
                rhrParams =
                    BaselineZScoreComputer.RhrZScoreParams(
                        currentNocturnalRhr = 55,
                        rhrValues = emptyList(),
                        frozenRhr = 55f,
                        effectiveRhrSigma = 3f,
                        baselineRhrValue = 55,
                    ),
            )

        assertNotNull(result.zHrv)
        assertEquals(0f, result.zHrv!!, 0.05f)
        assertNotNull(result.zRhr)
        assertEquals(0f, result.zRhr!!, 0.05f)
        assertEquals(0f, result.rhrDeltaBpm!!, 0.01f)
    }
}
