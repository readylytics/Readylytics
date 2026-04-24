package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.01f

class MathHelpersTest {
    @Test
    fun `median of empty list returns 0`() = assertEquals(0f, median(emptyList()), DELTA)

    @Test
    fun `median of single element`() = assertEquals(5f, median(listOf(5f)), DELTA)

    @Test
    fun `median of even-length list averages middle two`() = assertEquals(2f, median(listOf(1f, 3f)), DELTA)

    @Test
    fun `median of odd-length list returns middle element`() = assertEquals(2f, median(listOf(1f, 2f, 3f)), DELTA)

    @Test
    fun `median handles unsorted input`() = assertEquals(3f, median(listOf(5f, 1f, 3f)), DELTA)

    @Test
    fun `stdev of empty list returns 0`() = assertEquals(0f, stdev(emptyList()), DELTA)

    @Test
    fun `stdev of single element returns 0`() = assertEquals(0f, stdev(listOf(10f)), DELTA)

    @Test
    fun `stdev of two symmetric values`() = assertEquals(5f, stdev(listOf(10f, 20f)), DELTA)

    @Test
    fun `medianInt of empty list returns 0`() = assertEquals(0f, medianInt(emptyList()), DELTA)

    @Test
    fun `medianInt of odd list`() = assertEquals(50f, medianInt(listOf(40, 50, 60)), DELTA)

    @Test
    fun `medianInt of even list averages middle two`() = assertEquals(55f, medianInt(listOf(50, 60)), DELTA)
}

class StrainRatioTest {
    @Test
    fun `both zero returns 0`() = assertEquals(0f, computeStrainRatio(0f, 0f), DELTA)

    @Test
    fun `ctl zero returns 0`() = assertEquals(0f, computeStrainRatio(10f, 0f), DELTA)

    @Test
    fun `balanced training returns 1`() {
        // ATL=1.0/day, CTL=1.0/day → SR=1.0
        assertEquals(1.0f, computeStrainRatio(1f, 1f), DELTA)
    }

    @Test
    fun `high acute load`() {
        // ATL=1.5/day, CTL=1.0/day → SR=1.5
        assertEquals(1.5f, computeStrainRatio(1.5f, 1f), DELTA)
    }
}

class ComputeCtlTest {
    @Test
    fun `data tenure less than 7 returns seed`() =
        assertEquals(35f, computeCtlEma(dailyTrimpList = List(3) { 50f }), DELTA)

    @Test
    fun `data tenure between 7 and 21 returns cumulative mean`() {
        // 100 total TRIMP over 10 days = 10 TRIMP per day
        assertEquals(10f, computeCtlEma(dailyTrimpList = List(10) { 10f }), DELTA)
    }

    @Test
    fun `data tenure 21 or more returns EMA`() {
        // 21 days of 20 TRIMP = SMA of 20
        // Day 22: 40 TRIMP. Alpha = 2/(42+1) ≈ 0.0465
        // EMA = 40 * 0.0465 + 20 * (1 - 0.0465) = 1.86 + 19.07 = 20.93
        val dailyTrimpList = List(21) { 20f } + 40f
        assertEquals(20.93f, computeCtlEma(dailyTrimpList = dailyTrimpList), DELTA)
    }

    @Test
    fun `steady training gives SR close to 1`() {
        // 30 days of 10 TRIMP/day
        val dailyTrimpList = List(30) { 10f }
        val ctl = computeCtlEma(dailyTrimpList = dailyTrimpList)
        val atl = 10f // Average of any 7 days of 10/day is 10
        val sr = computeStrainRatio(atl, ctl)
        assertEquals(1.0f, sr, 0.05f)
    }
}

class DurationSubScoreTest {
    @Test
    fun `full goal sleep with high efficiency`() {
        // 8h / 8h goal, 85% efficiency → ratio=1.0, score = 1.0 * 100 * 0.85 = 85.0
        assertEquals(85f, computeDurationSubScore(480, 85f, 8f), DELTA)
    }

    @Test
    fun `half sleep goal`() {
        // 4h / 8h goal, 80% efficiency → ratio=0.5, score = 0.5 * 100 * 0.80 = 40.0
        assertEquals(40f, computeDurationSubScore(240, 80f, 8f), DELTA)
    }

    @Test
    fun `over-sleeping clamped at 1`() {
        // 10h / 8h goal (clamped to 1.0), 90% efficiency → 1.0 * 100 * 0.90 = 90.0
        assertEquals(90f, computeDurationSubScore(600, 90f, 8f), DELTA)
    }
}

class ArchSubScoreTest {
    @Test
    fun `at benchmark deep and rem`() {
        // 90 deep + 90 rem out of 450 total → deep=20%, rem=20% → 100
        assertEquals(100f, computeArchSubScore(90, 90, 450), DELTA)
    }

    @Test
    fun `no deep or rem`() = assertEquals(0f, computeArchSubScore(0, 0, 480), DELTA)

    @Test
    fun `zero duration guard`() = assertEquals(0f, computeArchSubScore(0, 0, 0), DELTA)

    @Test
    fun `excess deep sleep capped at 100`() {
        // deep=120/450=26.7% (>20%), rem=90/450=20% → both components 100 → 100
        assertEquals(100f, computeArchSubScore(120, 90, 450), DELTA)
    }
}

class RestorationSubScoreTest {
    @Test
    fun `neutral Z-score and ratio 1_0 gives midpoint`() {
        // HRV at baseline → Z=0 → hrv_score=50; RHR ratio=1.0 → rhr_score=100 → 75
        val hrv = listOf(60f, 60f, 60f)
        val rhr = listOf(50, 50, 50)
        assertEquals(75f, computeRestorationSubScore(60f, hrv, 50f, rhr, null, null), DELTA)
    }

    @Test
    fun `excellent HRV and low RHR capped at 100`() {
        // HRV Z=+2 → hrv_score=100; RHR ratio<1 → rhr_score=100 → 100
        val hrv = listOf(40f, 40f, 40f)
        val rhr = listOf(60, 60, 60)
        assertEquals(100f, computeRestorationSubScore(60f, hrv, 50f, rhr, null, null), DELTA)
    }

    @Test
    fun `poor HRV with elevated RHR gives partial score`() {
        // HRV at 40 vs baseline 80 → Z very negative → hrv_score=0 (clamped)
        // RHR night=60 vs baseline=50 → rhr_score = 50/60*100 ≈ 83.3
        // sRest = 0.5*0 + 0.5*83.3 ≈ 41.65
        val hrv = listOf(80f, 80f, 80f)
        val rhr = listOf(50, 50, 50)
        assertEquals(41.67f, computeRestorationSubScore(40f, hrv, 60f, rhr, null, null), DELTA)
    }

    @Test
    fun `rhr override respected`() {
        // override=50, ratio=50/50=1.0 → rhr_score=100; hrv neutral → 75
        val hrv = listOf(60f, 60f, 60f)
        assertEquals(75f, computeRestorationSubScore(60f, hrv, 50f, emptyList(), 50f, null), DELTA)
    }

    @Test
    fun `hrv override respected`() {
        // override baseline=60, stdHrv=1 → Z≈0 → hrv_score=50; rhr neutral → 75
        val rhr = listOf(50, 50, 50)
        assertEquals(75f, computeRestorationSubScore(60f, emptyList(), 50f, rhr, null, 60f), DELTA)
    }
}

class SleepScoreIntegrationTest {
    @Test
    fun `weighted sum of known sub-scores`() {
        // 96 deep + 96 rem out of 480 total = exactly 20% each → S'arch=100
        // 8h / 8h goal, 85% efficiency → S'dur = 1.0 * 100 * 0.85 = 85.0
        // HRV at baseline (Z=0) → hrv_score=50; RHR ratio=1.0 → rhr_score=100 → S'rest=75
        // SS = 0.5*85 + 0.25*100 + 0.25*75 = 86.25
        val score =
            computeSleepScore(
                durationMinutes = 480,
                efficiency = 85f,
                deepSleepMinutes = 96,
                remSleepMinutes = 96,
                goalSleepHours = 8f,
                sRest = 75f,
            )
        assertEquals(86.25f, score, DELTA)
    }
}
