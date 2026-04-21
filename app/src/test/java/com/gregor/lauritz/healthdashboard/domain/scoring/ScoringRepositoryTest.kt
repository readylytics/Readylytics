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
    fun `chronic zero returns 0`() = assertEquals(0f, computeStrainRatio(10f, 0f), DELTA)

    @Test
    fun `balanced training returns 1`() {
        // acuteSum=7 (1/day), chronicSum=42 (1/day) → SR=1.0
        assertEquals(1.0f, computeStrainRatio(7f, 42f), DELTA)
    }

    @Test
    fun `high acute load`() {
        // acuteSum=10.5 (1.5/day), chronicSum=42 (1.0/day) → SR=1.5
        assertEquals(1.5f, computeStrainRatio(10.5f, 42f), DELTA)
    }
}

class LoadScoreTest {
    @Test
    fun `zero SR is under-trained`() = assertEquals(80f, computeLoadScore(0f), DELTA)

    @Test
    fun `below 0_8 is under-trained`() = assertEquals(80f, computeLoadScore(0.5f), DELTA)

    @Test
    fun `at 0_8 boundary is optimal`() = assertEquals(100f, computeLoadScore(0.8f), DELTA)

    @Test
    fun `at 1_0 is optimal`() = assertEquals(100f, computeLoadScore(1.0f), DELTA)

    @Test
    fun `at 1_2 boundary is optimal`() = assertEquals(100f, computeLoadScore(1.2f), DELTA)

    @Test
    fun `at 1_35 is fatigued midpoint`() = assertEquals(70f, computeLoadScore(1.35f), DELTA)

    @Test
    fun `at 1_5 is minimum fatigued`() = assertEquals(40f, computeLoadScore(1.5f), DELTA)

    @Test
    fun `above 1_5 is over-reached`() = assertEquals(40f, computeLoadScore(2.0f), DELTA)
}

class DurationSubScoreTest {
    @Test
    fun `full goal sleep with high efficiency`() {
        // 8h / 8h goal, 85% efficiency → 0.7*100 + 0.3*85 = 95.5
        assertEquals(95.5f, computeDurationSubScore(480, 85f, 8f), DELTA)
    }

    @Test
    fun `half sleep goal`() {
        // 4h / 8h goal, 80% efficiency → 0.7*50 + 0.3*80 = 59.0
        assertEquals(59f, computeDurationSubScore(240, 80f, 8f), DELTA)
    }

    @Test
    fun `over-sleeping clamped at 1`() {
        // 10h / 8h goal (clamped to 1.0), 90% efficiency → 0.7*100 + 0.3*90 = 97.0
        assertEquals(97f, computeDurationSubScore(600, 90f, 8f), DELTA)
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
    fun `poor HRV and elevated RHR clamped at 0`() {
        // HRV Z=-2 → hrv_score=0; RHR ratio≥1.1 → rhr_score=0 → 0
        val hrv = listOf(80f, 80f, 80f)
        val rhr = listOf(50, 50, 50)
        assertEquals(0f, computeRestorationSubScore(40f, hrv, 60f, rhr, null, null), DELTA)
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
        // 8h / 8h goal, 85% efficiency → S'dur = 0.7*100 + 0.3*85 = 95.5
        // HRV at baseline (Z=0) → hrv_score=50; RHR ratio=1.0 → rhr_score=100 → S'rest=75
        // SS = 0.5*95.5 + 0.25*100 + 0.25*75 = 91.5
        val hrv = listOf(60f, 60f, 60f)
        val rhr = listOf(50, 50, 50)
        val score =
            computeSleepScore(
                durationMinutes = 480,
                efficiency = 85f,
                deepSleepMinutes = 96,
                remSleepMinutes = 96,
                goalSleepHours = 8f,
                currentHrvMean = 60f,
                hrvValues = hrv,
                currentNocturnalRhr = 50f,
                rhrValues = rhr,
                rhrBaselineOverride = null,
                hrvBaselineOverride = null,
            )
        assertEquals(91.5f, score, DELTA)
    }
}
