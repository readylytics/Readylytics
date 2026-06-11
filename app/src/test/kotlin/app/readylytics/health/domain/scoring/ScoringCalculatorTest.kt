package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.PaiScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import app.readylytics.health.domain.util.median
import app.readylytics.health.domain.util.stdev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private const val DELTA = 0.5f

private val calculator =
    CompositeScoringCalculator(
        SleepScoringStrategy(LoadScoringStrategy()),
        PaiScoringStrategy(),
        LoadScoringStrategy(),
    )

// ─── Math helpers ─────────────────────────────────────────────────────────────
class MathHelpersTest {
    @Test
    fun `median of empty list returns 0`() = assertEquals(0f, emptyList<Float>().median(), DELTA)

    @Test
    fun `median of single element`() = assertEquals(5f, listOf(5f).median(), DELTA)

    @Test
    fun `median of even-length list averages middle two`() = assertEquals(2f, listOf(1f, 3f).median(), DELTA)

    @Test
    fun `median of odd-length list returns middle element`() = assertEquals(2f, listOf(1f, 2f, 3f).median(), DELTA)

    @Test
    fun `median handles unsorted input`() = assertEquals(3f, listOf(5f, 1f, 3f).median(), DELTA)

    @Test
    fun `stdev of empty list returns 0`() = assertEquals(0f, emptyList<Float>().stdev(), DELTA)

    @Test
    fun `stdev of single element returns 0`() = assertEquals(0f, listOf(10f).stdev(), DELTA)

    @Test
    fun `stdev of two symmetric values`() = assertEquals(7.071f, listOf(10f, 20f).stdev(), DELTA)

    @Test
    fun `medianInt of empty list returns 0`() = assertEquals(0f, emptyList<Int>().median(), DELTA)

    @Test
    fun `medianInt of odd list`() = assertEquals(50f, listOf(40, 50, 60).median(), DELTA)

    @Test
    fun `medianInt of even list averages middle two`() = assertEquals(55f, listOf(50, 60).median(), DELTA)
}

// ─── Strain Ratio ─────────────────────────────────────────────────────────────
class StrainRatioTest {
    @Test
    fun `both zero returns 0`() = assertEquals(0f, calculator.computeStrainRatio(0f, 0f), DELTA)

    @Test
    fun `ctl zero returns 0`() = assertEquals(0f, calculator.computeStrainRatio(10f, 0f), DELTA)

    @Test
    fun `balanced training returns 1`() = assertEquals(1.0f, calculator.computeStrainRatio(1f, 1f), DELTA)

    @Test
    fun `high acute load`() = assertEquals(1.5f, calculator.computeStrainRatio(1.5f, 1f), DELTA)
}

// ─── CTL EMA ──────────────────────────────────────────────────────────────────
class ComputeCtlTest {
    @Test
    fun `data tenure less than 7 returns seed`() =
        assertEquals(35f, calculator.computeCtlEma(dailyTrimpList = List(3) { 50f }), DELTA)

    @Test
    fun `data tenure between 7 and 21 returns cumulative mean`() =
        assertEquals(10f, calculator.computeCtlEma(dailyTrimpList = List(10) { 10f }), DELTA)

    @Test
    fun `data tenure 21 or more returns EMA`() {
        val dailyTrimpList = List(21) { 20f } + 40f
        assertEquals(20.93f, calculator.computeCtlEma(dailyTrimpList = dailyTrimpList), DELTA)
    }

    @Test
    fun `steady training gives SR close to 1`() {
        val dailyTrimpList = List(30) { 10f }
        val ctl = calculator.computeCtlEma(dailyTrimpList = dailyTrimpList)
        val sr = calculator.computeStrainRatio(10f, ctl)
        assertEquals(1.0f, sr, 0.05f)
    }

    @Test
    fun `computeEmaWithDecay utilizes extended history for stabilization`() {
        val rangeEnd = LocalDate.of(2023, 12, 31)
        val windowDays = 42L // CTL standard
        val fullHistory = mutableMapOf<LocalDate, Float>()
        for (i in 60 until 120) {
            fullHistory[rangeEnd.minusDays(i.toLong())] = 200f
        }
        for (i in 0 until 60) {
            fullHistory[rangeEnd.minusDays(i.toLong())] = 100f
        }
        val scoreTruncated = 100f
        val scoreWithHistory = calculator.computeCtlEmaWithDecay(fullHistory, rangeEnd, windowDays)
        assertTrue(
            "CTL should be elevated by historical high-load block (found: $scoreWithHistory)",
            scoreWithHistory > scoreTruncated,
        )
    }
}

// ─── Duration sub-score (additive efficiency) ─────────────────────────────────
class DurationSubScoreTest {
    @Test
    fun `full goal sleep with excellent efficiency`() {
        assertEquals(100f, calculator.computeDurationSubScore(480, 90f, 8f), DELTA)
    }

    @Test
    fun `full goal sleep with good efficiency`() {
        assertEquals(95.5f, calculator.computeDurationSubScore(480, 85f, 8f), DELTA)
    }

    @Test
    fun `half sleep goal with fair efficiency`() {
        assertEquals(54.5f, calculator.computeDurationSubScore(240, 80f, 8f), DELTA)
    }

    @Test
    fun `over-sleeping clamped at 100`() {
        assertEquals(100f, calculator.computeDurationSubScore(600, 90f, 8f), DELTA)
    }

    @Test
    fun `poor efficiency drags down score`() {
        assertEquals(74.5f, calculator.computeDurationSubScore(480, 60f, 8f), DELTA)
    }
}

// ─── Night Validation ─────────────────────────────────────────────────────────
class NightValidationTest {
    @Test
    fun `perfect night is valid`() {
        val res = calculator.validateNight(60f, 50f, 480, 60, 60)
        assertTrue(res.rmssdValid)
        assertTrue(res.rhrValid)
        assertTrue(res.durationValid)
        assertTrue(res.stagesValid)
        assertTrue(res.canContributeToBaseline)
    }

    @Test
    fun `short sleep cannot contribute to baseline`() {
        val res = calculator.validateNight(60f, 50f, 180, 30, 30)
        assertTrue(!res.durationValid)
        assertTrue(!res.canContributeToBaseline)
    }

    @Test
    fun `impossible deep sleep fraction is invalid`() {
        val res = calculator.validateNight(60f, 50f, 400, 201, 40)
        assertTrue(!res.stagesValid)
    }

    @Test
    fun `outlier RMSSD is invalid`() {
        val res = calculator.validateNight(300f, 50f, 400, 40, 40)
        assertTrue(!res.rmssdValid)
    }
}

// ─── Architecture sub-score (age-banded denominators) ─────────────────────────
class ArchSubScoreTest {
    @Test
    fun `at age-banded targets for under-30 scores 100`() {
        assertEquals(100f, calculator.computeArchSubScore(20, 22, 100, 25), DELTA)
    }

    @Test
    fun `at age-banded targets for age 40 scores 100`() {
        assertEquals(100f, calculator.computeArchSubScore(18, 21, 100, 40), DELTA)
    }

    @Test
    fun `at age-banded targets for age 70 scores 100`() {
        assertEquals(100f, calculator.computeArchSubScore(12, 19, 100, 70), DELTA)
    }

    @Test
    fun `no deep or rem scores 0`() = assertEquals(0f, calculator.computeArchSubScore(0, 0, 480), DELTA)

    @Test
    fun `zero duration guard`() = assertEquals(0f, calculator.computeArchSubScore(0, 0, 0), DELTA)

    @Test
    fun `excess deep sleep is capped at target, not penalised`() {
        val score = calculator.computeArchSubScore(30, 20, 100, 25)
        assertEquals(95.45f, score, DELTA)
    }

    @Test
    fun `older user not penalised for lower deep sleep typical of age`() {
        val score70 = calculator.computeArchSubScore(12, 18, 100, 70)
        val score25 = calculator.computeArchSubScore(12, 18, 100, 25)
        assertTrue("70yo should score at least as high as 25yo for same sleep", score70 >= score25)
    }
}

// ─── Restoration sub-score (lnRMSSD + RHR Z-score) ───────────────────────────
class RestorationSubScoreTest {
    @Test
    fun `both signals at personal baseline return 50`() {
        val hrv = listOf(60f, 60f, 60f)
        val rhr = listOf(60, 60, 60)
        assertEquals(
            50f,
            calculator.computeRestorationSubScore(
                currentHrvMean = 60f,
                muHrvHistory = hrv,
                sigmaHrvHistory = hrv,
                currentNocturnalRhr = 60f,
                rhrValues = rhr,
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            ),
            DELTA,
        )
    }

    @Test
    fun `elevated HRV above personal mean raises score above 50`() {
        val hrv = listOf(40f, 40f, 40f, 40f, 40f)
        val rhr = listOf(60, 60, 60, 60, 60)
        val score =
            calculator.computeRestorationSubScore(
                currentHrvMean = 60f,
                muHrvHistory = hrv,
                sigmaHrvHistory = hrv,
                currentNocturnalRhr = 60f,
                rhrValues = rhr,
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            )
        assertTrue("Score should be above 50 with HRV above baseline, was $score", score > 50f)
    }

    @Test
    fun `elevated nocturnal RHR lowers score below 50`() {
        val hrv = listOf(60f, 60f, 60f, 60f, 60f, 60f, 60f, 60f)
        val rhr = listOf(55, 55, 55, 55, 55, 55, 55, 55)
        val score =
            calculator.computeRestorationSubScore(
                currentHrvMean = 60f,
                muHrvHistory = hrv,
                sigmaHrvHistory = hrv,
                currentNocturnalRhr = 70f,
                rhrValues = rhr,
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            )
        assertTrue("Score should be below 50 with elevated RHR, was $score", score < 50f)
    }

    @Test
    fun `rhr override respected`() {
        val score =
            calculator.computeRestorationSubScore(
                currentHrvMean = 60f,
                muHrvHistory = listOf(60f, 60f, 60f),
                sigmaHrvHistory = listOf(60f, 60f, 60f),
                currentNocturnalRhr = 60f,
                rhrValues = emptyList(),
                rhrBaselineOverride = 60f,
                hrvBaselineOverride = null,
            )
        assertEquals(50f, score, DELTA)
    }

    @Test
    fun `hrv override respected`() {
        val score =
            calculator.computeRestorationSubScore(
                currentHrvMean = 60f,
                muHrvHistory = emptyList(),
                sigmaHrvHistory = emptyList(),
                currentNocturnalRhr = 60f,
                rhrValues = listOf(60, 60, 60),
                rhrBaselineOverride = null,
                hrvBaselineOverride = 60f,
            )
        assertEquals(50f, score, DELTA)
    }

    @Test
    fun `hrv z-score is invariant to uniform scaling of all values`() {
        val hrv = listOf(40f, 50f, 60f, 55f, 45f)
        val score1 =
            calculator.computeRestorationSubScore(
                currentHrvMean = 50f,
                muHrvHistory = hrv,
                sigmaHrvHistory = hrv,
                currentNocturnalRhr = 60f,
                rhrValues = listOf(60, 60, 60),
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            )
        val scaledHrv = hrv.map { it * 2f }
        val score2 =
            calculator.computeRestorationSubScore(
                currentHrvMean = 100f,
                muHrvHistory = scaledHrv,
                sigmaHrvHistory = scaledHrv,
                currentNocturnalRhr = 60f,
                rhrValues = listOf(60, 60, 60),
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            )
        assertEquals(score1, score2, DELTA)
    }
}

// ─── HRV Z-score helper ───────────────────────────────────────────────────────
class HrvZScoreTest {
    @Test
    fun `returns null when muHistory is empty and no override`() {
        assertNull(calculator.computeHrvZScore(50f, emptyList(), emptyList(), baselineOverride = null))
    }

    @Test
    fun `z-score is zero when current equals historical mean`() {
        val history = List(10) { 50f }
        val z = calculator.computeHrvZScore(50f, history, history)
        assertNotNull(z)
        assertEquals(0f, z!!, DELTA)
    }

    @Test
    fun `positive z-score when current above mean`() {
        val history = List(10) { 40f }
        val z = calculator.computeHrvZScore(60f, history, history)
        assertTrue("Z should be positive, was $z", z!! > 0f)
    }

    @Test
    fun `negative z-score when current below mean`() {
        val history = List(10) { 60f }
        val z = calculator.computeHrvZScore(40f, history, history)
        assertTrue("Z should be negative, was $z", z!! < 0f)
    }
}

// ─── Sleep Score integration ──────────────────────────────────────────────────
class SleepScoreIntegrationTest {
    @Test
    fun `weighted sum of known sub-scores at age 25`() {
        val score =
            calculator.computeSleepScore(
                durationMinutes = 480,
                efficiency = 85f,
                deepSleepMinutes = 96,
                remSleepMinutes = 96,
                goalSleepHours = 8f,
                sRest = 75f,
                userAge = 25,
            )
        assertEquals(90.36f, score, DELTA)
    }

    @Test
    fun `perfect sleep at age 25 hits benchmark targets`() {
        val score =
            calculator.computeSleepScore(
                durationMinutes = 100,
                efficiency = 90f,
                deepSleepMinutes = 20,
                remSleepMinutes = 22,
                goalSleepHours = (100f / 60f),
                sRest = 100f,
                userAge = 25,
            )
        assertEquals(100f, score, DELTA)
    }
}
