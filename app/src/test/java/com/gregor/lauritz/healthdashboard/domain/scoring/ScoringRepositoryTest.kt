package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
import com.gregor.lauritz.healthdashboard.domain.util.stdev
import org.junit.Assert.assertEquals
import org.junit.Test

private const val DELTA = 0.01f

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

class StrainRatioTest {
    @Test
    fun `both zero returns 0`() = assertEquals(0f, ScoringCalculator.computeStrainRatio(0f, 0f), DELTA)

    @Test
    fun `ctl zero returns 0`() = assertEquals(0f, ScoringCalculator.computeStrainRatio(10f, 0f), DELTA)

    @Test
    fun `balanced training returns 1`() {
        assertEquals(1.0f, ScoringCalculator.computeStrainRatio(1f, 1f), DELTA)
    }

    @Test
    fun `high acute load`() {
        assertEquals(1.5f, ScoringCalculator.computeStrainRatio(1.5f, 1f), DELTA)
    }
}

class ComputeCtlTest {
    @Test
    fun `data tenure less than 7 returns seed`() =
        assertEquals(35f, ScoringCalculator.computeCtlEma(dailyTrimpList = List(3) { 50f }), DELTA)

    @Test
    fun `data tenure between 7 and 21 returns cumulative mean`() {
        assertEquals(10f, ScoringCalculator.computeCtlEma(dailyTrimpList = List(10) { 10f }), DELTA)
    }

    @Test
    fun `data tenure 21 or more returns EMA`() {
        val dailyTrimpList = List(21) { 20f } + 40f
        assertEquals(20.93f, ScoringCalculator.computeCtlEma(dailyTrimpList = dailyTrimpList), DELTA)
    }

    @Test
    fun `steady training gives SR close to 1`() {
        val dailyTrimpList = List(30) { 10f }
        val ctl = ScoringCalculator.computeCtlEma(dailyTrimpList = dailyTrimpList)
        val atl = 10f
        val sr = ScoringCalculator.computeStrainRatio(atl, ctl)
        assertEquals(1.0f, sr, 0.05f)
    }
}

class DurationSubScoreTest {
    @Test
    fun `full goal sleep with high efficiency`() {
        assertEquals(85f, ScoringCalculator.computeDurationSubScore(480, 85f, 8f), DELTA)
    }

    @Test
    fun `half sleep goal`() {
        assertEquals(40f, ScoringCalculator.computeDurationSubScore(240, 80f, 8f), DELTA)
    }

    @Test
    fun `over-sleeping clamped at 1`() {
        assertEquals(90f, ScoringCalculator.computeDurationSubScore(600, 90f, 8f), DELTA)
    }
}

class ArchSubScoreTest {
    @Test
    fun `at benchmark deep and rem`() {
        assertEquals(100f, ScoringCalculator.computeArchSubScore(90, 90, 450), DELTA)
    }

    @Test
    fun `no deep or rem`() = assertEquals(0f, ScoringCalculator.computeArchSubScore(0, 0, 480), DELTA)

    @Test
    fun `zero duration guard`() = assertEquals(0f, ScoringCalculator.computeArchSubScore(0, 0, 0), DELTA)

    @Test
    fun `excess deep sleep capped at 100`() {
        assertEquals(100f, ScoringCalculator.computeArchSubScore(120, 90, 450), DELTA)
    }
}

class RestorationSubScoreTest {
    @Test
    fun `neutral Z-score and ratio 1_0 gives midpoint`() {
        val hrv = listOf(60f, 60f, 60f)
        val rhr = listOf(50, 50, 50)
        assertEquals(75f, ScoringCalculator.computeRestorationSubScore(60f, hrv, 50f, rhr, null, null), DELTA)
    }

    @Test
    fun `excellent HRV and low RHR capped at 100`() {
        val hrv = listOf(40f, 40f, 40f)
        val rhr = listOf(60, 60, 60)
        assertEquals(100f, ScoringCalculator.computeRestorationSubScore(60f, hrv, 50f, rhr, null, null), DELTA)
    }

    @Test
    fun `poor HRV with elevated RHR gives partial score`() {
        val hrv = listOf(80f, 80f, 80f)
        val rhr = listOf(50, 50, 50)
        assertEquals(41.67f, ScoringCalculator.computeRestorationSubScore(40f, hrv, 60f, rhr, null, null), DELTA)
    }

    @Test
    fun `rhr override respected`() {
        val hrv = listOf(60f, 60f, 60f)
        assertEquals(75f, ScoringCalculator.computeRestorationSubScore(60f, hrv, 50f, emptyList(), 50f, null), DELTA)
    }

    @Test
    fun `hrv override respected`() {
        val rhr = listOf(50, 50, 50)
        assertEquals(75f, ScoringCalculator.computeRestorationSubScore(60f, emptyList(), 50f, rhr, null, 60f), DELTA)
    }
}

class SleepScoreIntegrationTest {
    @Test
    fun `weighted sum of known sub-scores`() {
        val score =
            ScoringCalculator.computeSleepScore(
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
