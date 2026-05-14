package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.util.median
import com.gregor.lauritz.healthdashboard.domain.util.stdev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private const val DELTA = 0.5f
private val calculator = ScoringCalculatorImpl()

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

        // Scenario: Athlete had a massive training block (200 TRIMP) 60-120 days ago,
        // then settled into a moderate block (100 TRIMP) for the last 60 days.
        val fullHistory = mutableMapOf<LocalDate, Float>()
        for (i in 60 until 120) {
            fullHistory[rangeEnd.minusDays(i.toLong())] = 200f
        }
        for (i in 0 until 60) {
            fullHistory[rangeEnd.minusDays(i.toLong())] = 100f
        }

        // If we only used 42 days (the window size), rangeStart would be rangeEnd - 41.
        // All values in that window are 100f, so the EMA would be exactly 100f.
        val scoreTruncated = 100f

        // With the fix, it should find the data starting 120 days ago and process the decay
        // of the 200f block into the 100f block.
        val scoreWithHistory = calculator.computeCtlEmaWithDecay(fullHistory, rangeEnd, windowDays)

        // The 200f block from 60 days ago should still have some residual impact, making the CTL > 100.
        assertTrue(
            "CTL should be elevated by historical high-load block (found: $scoreWithHistory)",
            scoreWithHistory > scoreTruncated,
        )
    }
}

// ─── Duration sub-score (additive efficiency) ─────────────────────────────────
// Formula: 0.7 * TST_term + 0.3 * eff_banded
// REF: Buysse 1989 PSQI; A.3 — avoids double-penalty since TST = TIB × SE

class DurationSubScoreTest {
    @Test
    fun `full goal sleep with excellent efficiency`() {
        // TST_term=100, eff=90% → 100. 0.7*100 + 0.3*100 = 100
        assertEquals(100f, calculator.computeDurationSubScore(480, 90f, 8f), DELTA)
    }

    @Test
    fun `full goal sleep with good efficiency`() {
        // TST_term=100, eff=85% → 85. 0.7*100 + 0.3*85 = 95.5
        assertEquals(95.5f, calculator.computeDurationSubScore(480, 85f, 8f), DELTA)
    }

    @Test
    fun `half sleep goal with fair efficiency`() {
        // TST_term=50 (4h / 8h), eff=80% → 65. 0.7*50 + 0.3*65 = 54.5
        assertEquals(54.5f, calculator.computeDurationSubScore(240, 80f, 8f), DELTA)
    }

    @Test
    fun `over-sleeping clamped at 100`() {
        // TST_term clamped to 100, eff=90% → 100. 0.7*100 + 0.3*100 = 100
        assertEquals(100f, calculator.computeDurationSubScore(600, 90f, 8f), DELTA)
    }

    @Test
    fun `poor efficiency drags down score`() {
        // TST_term=100, eff=60% → 15 (VERY_POOR). 0.7*100 + 0.3*15 = 74.5
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
        // 3h sleep < 4h threshold
        val res = calculator.validateNight(60f, 50f, 180, 30, 30)
        assertTrue(res.rmssdValid)
        assertTrue(!res.durationValid)
        assertTrue(!res.canContributeToBaseline)
    }

    @Test
    fun `impossible deep sleep fraction is invalid`() {
        // 50% deep > 40% threshold
        val res = calculator.validateNight(60f, 50f, 400, 201, 40)
        assertTrue(!res.stagesValid)
    }

    @Test
    fun `outlier RMSSD is invalid`() {
        val res = calculator.validateNight(300f, 50f, 400, 40, 40)
        assertTrue(!res.rmssdValid)
        assertTrue(!res.canContributeToBaseline)
    }
}

// ─── Architecture sub-score (age-banded denominators) ─────────────────────────
// REF: Ohayon 2004 Sleep 27:1255; Boulos 2019 Lancet Respir Med 7:533

class ArchSubScoreTest {
    @Test
    fun `at age-banded targets for under-30 scores 100`() {
        // age=25: deep target=18%, rem target=22%; 18 and 22 of 100 min TST
        assertEquals(100f, calculator.computeArchSubScore(18, 22, 100, 25), DELTA)
    }

    @Test
    fun `at age-banded targets for age 40 scores 100`() {
        // age=40: deep target=16%, rem target=21%
        assertEquals(100f, calculator.computeArchSubScore(16, 21, 100, 40), DELTA)
    }

    @Test
    fun `at age-banded targets for age 70 scores 100`() {
        // age=70: deep target=12%, rem target=18%
        assertEquals(100f, calculator.computeArchSubScore(12, 18, 100, 70), DELTA)
    }

    @Test
    fun `no deep or rem scores 0`() = assertEquals(0f, calculator.computeArchSubScore(0, 0, 480), DELTA)

    @Test
    fun `zero duration guard`() = assertEquals(0f, calculator.computeArchSubScore(0, 0, 0), DELTA)

    @Test
    fun `excess deep sleep is capped at target, not penalised`() {
        // deep=30% > target of 18% → capped at 100; rem=20% < target of 22% → 90.9
        val score = calculator.computeArchSubScore(30, 20, 100, 25)
        assertEquals(95.45f, score, DELTA)
    }

    @Test
    fun `older user not penalised for lower deep sleep typical of age`() {
        // age=70 target is 12%; 12% deep = perfect score component of 100
        val score70 = calculator.computeArchSubScore(12, 18, 100, 70)
        val score25 = calculator.computeArchSubScore(12, 18, 100, 25)
        // 70yo scores 100; 25yo scores lower (12% < 18% target)
        assertTrue("70yo should score at least as high as 25yo for same sleep", score70 >= score25)
    }
}

// ─── Restoration sub-score (lnRMSSD + RHR Z-score) ───────────────────────────
// HRV operates on ln(RMSSD) internally; display values remain in raw ms.
// RHR uses Z-score: elevated RHR → positive Z → lower score.
// REF: Plews 2013 Sports Med 43:773; Mishra 2020 Nat Biomed Eng

class RestorationSubScoreTest {
    @Test
    fun `both signals at personal baseline return 50`() {
        // Z_hrv = 0, Z_rhr = 0 → 0.5*50 + 0.5*50 = 50
        val hrv = listOf(60f, 60f, 60f)
        val rhr = listOf(60, 60, 60)
        assertEquals(
            50f,
            calculator.computeRestorationSubScore(
                60f,
                hrv,
                hrv,
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
        // currentHrv > mean(history) → positive Z_hrv → higher restoration
        val hrv = listOf(40f, 40f, 40f, 40f, 40f)
        val rhr = listOf(60, 60, 60, 60, 60)
        val score =
            calculator.computeRestorationSubScore(
                60f,
                hrv,
                hrv,
                currentNocturnalRhr = 60f,
                rhrValues = rhr,
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            )
        assertTrue("Score should be above 50 with HRV above baseline, was $score", score > 50f)
    }

    @Test
    fun `elevated nocturnal RHR lowers score below 50`() {
        // currentRhr > baseline → positive Z_rhr → lower restoration
        val hrv = listOf(60f, 60f, 60f)
        val rhr = listOf(55, 55, 55, 55, 55, 55, 55, 55)
        val score =
            calculator.computeRestorationSubScore(
                60f,
                hrv,
                hrv,
                currentNocturnalRhr = 70f,
                rhrValues = rhr,
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            )
        assertTrue("Score should be below 50 with elevated RHR, was $score", score < 50f)
    }

    @Test
    fun `rhr override respected`() {
        // Override baseline=60, currentRhr=60 → Z_rhr=0; HRV at baseline → Z_hrv=0 → sRest=50
        val hrv = listOf(60f, 60f, 60f)
        assertEquals(
            50f,
            calculator.computeRestorationSubScore(
                60f,
                hrv,
                hrv,
                currentNocturnalRhr = 60f,
                rhrValues = emptyList(),
                rhrBaselineOverride = 60f,
                hrvBaselineOverride = null,
            ),
            DELTA,
        )
    }

    @Test
    fun `hrv override respected`() {
        // Override baseline=60ms, currentHrv=60ms → Z_hrv=0; RHR at baseline → sRest=50
        val rhr = listOf(60, 60, 60)
        assertEquals(
            50f,
            calculator.computeRestorationSubScore(
                60f,
                emptyList(),
                emptyList(),
                currentNocturnalRhr = 60f,
                rhrValues = rhr,
                rhrBaselineOverride = null,
                hrvBaselineOverride = 60f,
            ),
            DELTA,
        )
    }

    @Test
    fun `hrv z-score is invariant to uniform scaling of all values`() {
        // ln(k*x) - ln(k*mu) = ln(x) - ln(mu) → scaling history and today by same factor leaves Z unchanged
        val hrv = listOf(40f, 50f, 60f, 55f, 45f)
        val rhr = listOf(60, 60, 60, 60, 60)
        val score1 =
            calculator.computeRestorationSubScore(
                50f,
                hrv,
                hrv,
                currentNocturnalRhr = 60f,
                rhrValues = rhr,
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            )
        val scaledHrv = hrv.map { it * 2f }
        val score2 =
            calculator.computeRestorationSubScore(
                100f,
                scaledHrv,
                scaledHrv,
                currentNocturnalRhr = 60f,
                rhrValues = rhr,
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
        assertNotNull(z)
        assertTrue("Z should be positive, was $z", z!! > 0f)
    }

    @Test
    fun `negative z-score when current below mean`() {
        val history = List(10) { 60f }
        val z = calculator.computeHrvZScore(40f, history, history)
        assertNotNull(z)
        assertTrue("Z should be negative, was $z", z!! < 0f)
    }
}

// ─── Sleep Score integration ──────────────────────────────────────────────────

class SleepScoreIntegrationTest {
    @Test
    fun `weighted sum of known sub-scores at age 25`() {
        // sDur: TST=480min, goal=8h → TST_term=100; eff=85% → 85; 0.7*100+0.3*85 = 95.5
        // sArch: deep=96/480=20%, rem=96/480=20%; age=25 → deepTarget=18%, remTarget=22%
        //   deepComp = (0.20/0.18)→capped→100; remComp = (0.20/0.22)*100 = 90.9
        //   sArch = 0.5*100 + 0.5*90.9 = 95.45
        // SS = 0.50*95.5 + 0.25*95.45 + 0.25*75 = 47.75 + 23.86 + 18.75 = 90.36
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
        // TST_term=100, eff=90%→100, sDur=100
        // deep=18%, rem=22% of 100min TST → both hit age-25 targets → sArch=100
        // SS = 0.5*100 + 0.25*100 + 0.25*100 = 100
        val score =
            calculator.computeSleepScore(
                durationMinutes = 100,
                efficiency = 90f,
                deepSleepMinutes = 18,
                remSleepMinutes = 22,
                goalSleepHours = (100f / 60f),
                sRest = 100f,
                userAge = 25,
            )
        assertEquals(100f, score, DELTA)
    }
}
