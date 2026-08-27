package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.scoring.domain.scoring.components.RestorationWeights
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorationScoreAssemblerTest {
    private val calculator =
        CompositeScoringCalculator(
            SleepScoringStrategy(LoadScoringStrategy()),
            RasScoringStrategy(),
            LoadScoringStrategy(),
        )

    private val assembler = RestorationScoreAssembler(calculator)

    @Test
    fun `assembleRestorationScore with balanced z-scores produces neutral score of 50`() {
        val result =
            assembler.assembleRestorationScore(
                RestorationScoreAssembler.RestorationParams(
                    zHrv = 0f,
                    zRhr = 0f,
                ),
            )

        assertNotNull(result.hrvScore)
        assertNotNull(result.rhrScore)
        assertEquals(50f, result.hrvScore!!, 0.1f)
        assertEquals(50f, result.rhrScore!!, 0.1f)
        assertEquals(50f, result.sRest, 0.1f)
    }

    @Test
    fun `assembleRestorationScore with positive HRV and negative RHR produces elevated restoration score`() {
        val result =
            assembler.assembleRestorationScore(
                RestorationScoreAssembler.RestorationParams(
                    zHrv = 1.0f,
                    zRhr = -1.0f,
                ),
            )

        val hrvScore = requireNotNull(result.hrvScore)
        val rhrScore = requireNotNull(result.rhrScore)
        assertTrue(result.sRest > 50f)
        assertTrue(hrvScore > 50f)
        assertTrue(rhrScore > 50f)
        assertEquals(75f, rhrScore, 0.1f)
    }

    @Test
    fun `assembleRestorationScore applies late nadir penalty`() {
        val normalResult =
            assembler.assembleRestorationScore(
                RestorationScoreAssembler.RestorationParams(
                    zHrv = 0f,
                    zRhr = 0f,
                    isLateNadir = false,
                ),
            )

        val lateNadirResult =
            assembler.assembleRestorationScore(
                RestorationScoreAssembler.RestorationParams(
                    zHrv = 0f,
                    zRhr = 0f,
                    isLateNadir = true,
                ),
            )

        val expected = normalResult.sRest * ScoringConstants.Restoration.LATE_NADIR_PENALTY
        assertEquals(expected, lateNadirResult.sRest, 0.01f)
        assertEquals(47.5f, lateNadirResult.sRest, 0.01f)
    }

    @Test
    fun `assembleRestorationScore respects custom restoration weights`() {
        val customWeights = RestorationWeights(hrvWeight = 0.8f, rhrWeight = 0.2f)

        val result =
            assembler.assembleRestorationScore(
                RestorationScoreAssembler.RestorationParams(
                    zHrv = 1.0f,
                    zRhr = 0f,
                    restorationWeights = customWeights,
                ),
            )

        val expectedHrvScore = calculator.computeHrvScore(1.0f)
        val expectedRhrScore = 50f
        val expectedRest = 0.8f * expectedHrvScore + 0.2f * expectedRhrScore

        assertEquals(expectedRest, result.sRest, 0.01f)
    }

    @Test
    fun `assembleRestorationScore handles null z-scores gracefully with fallback`() {
        val result =
            assembler.assembleRestorationScore(
                RestorationScoreAssembler.RestorationParams(
                    zHrv = null,
                    zRhr = null,
                ),
            )

        assertNull(result.hrvScore)
        assertNull(result.rhrScore)
        assertEquals(50f, result.sRest, 0.1f)
    }
}
