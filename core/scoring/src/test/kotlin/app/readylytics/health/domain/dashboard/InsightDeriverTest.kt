package app.readylytics.health.domain.dashboard

import app.readylytics.health.domain.insights.InsightFinding
import app.readylytics.health.domain.insights.InsightParams
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightDeriverTest {
    @Test
    fun `empty inputs return empty result`() {
        val result = InsightDeriver.derive(emptySet(), dismissedTypes = emptySet())
        assertTrue(result.active.isEmpty())
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `null flags are treated as empty`() {
        val result = InsightDeriver.derive(null, dismissedTypes = emptySet())
        assertTrue(result.active.isEmpty())
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `non-insight recovery flags are ignored`() {
        val flags = setOf(RecoveryFlag.CALIBRATING, RecoveryFlag.HRV_MISSING)
        val result = InsightDeriver.derive(flags, dismissedTypes = emptySet())
        assertTrue(result.active.isEmpty())
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `active insights without dismissals are queued in display priority`() {
        val flags = setOf(RecoveryFlag.STRONG_RECOVERY_SIGNAL, RecoveryFlag.ILLNESS_ONSET, RecoveryFlag.NADIR_DELAYED)
        val result = InsightDeriver.derive(flags, dismissedTypes = emptySet())
        assertEquals(
            setOf(InsightType.LATE_NADIR, InsightType.SICK_INDICATOR, InsightType.STRONG_RECOVERY_SIGNAL),
            result.active,
        )
        assertEquals(
            listOf(InsightType.SICK_INDICATOR, InsightType.STRONG_RECOVERY_SIGNAL, InsightType.LATE_NADIR),
            result.visibleQueue,
        )
        assertEquals(InsightType.SICK_INDICATOR, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `dismissing current insight rotates to next queued insight`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED, RecoveryFlag.ILLNESS_ONSET, RecoveryFlag.STRONG_RECOVERY_SIGNAL)
        val dismissed = setOf(InsightType.SICK_INDICATOR)
        val result = InsightDeriver.derive(flags, dismissedTypes = dismissed)

        assertEquals(
            setOf(InsightType.LATE_NADIR, InsightType.SICK_INDICATOR, InsightType.STRONG_RECOVERY_SIGNAL),
            result.active,
        )
        assertEquals(listOf(InsightType.STRONG_RECOVERY_SIGNAL, InsightType.LATE_NADIR), result.visibleQueue)
        assertEquals(InsightType.STRONG_RECOVERY_SIGNAL, result.current)
        assertEquals(1, result.dismissedCount)
    }

    @Test
    fun `legacy overreaching flag does not produce a visible insight`() {
        val result = InsightDeriver.derive(setOf(RecoveryFlag.OVERREACHING), dismissedTypes = emptySet())

        assertTrue(result.active.isEmpty())
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
    }

    @Test
    fun `fully dismissed active insights leave rerun state`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED)
        val dismissed = setOf(InsightType.LATE_NADIR)
        val result = InsightDeriver.derive(flags, dismissedTypes = dismissed)

        assertEquals(setOf(InsightType.LATE_NADIR), result.active)
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
        assertEquals(1, result.dismissedCount)
    }

    @Test
    fun `dismissals of non-active insights are ignored in count`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED)
        val dismissed = setOf(InsightType.SICK_INDICATOR)
        val result = InsightDeriver.derive(flags, dismissedTypes = dismissed)

        assertEquals(setOf(InsightType.LATE_NADIR), result.active)
        assertEquals(listOf(InsightType.LATE_NADIR), result.visibleQueue)
        assertEquals(InsightType.LATE_NADIR, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `circadian shift finding suppresses rest day no impact`() {
        val flags = setOf(RecoveryFlag.REST_DAY_NO_IMPACT)
        val finding =
            InsightFinding(
                InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS,
                InsightParams.CircadianShift(bedtimeOffsetMinutes = 105),
            )
        val result = InsightDeriver.derive(flags, listOf(finding), dismissedTypes = emptySet())

        assertEquals(setOf(InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS), result.active)
        assertEquals(listOf(InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS), result.visibleQueue)
        assertEquals(InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS, result.current)
        assertEquals(InsightParams.CircadianShift(bedtimeOffsetMinutes = 105), result.currentParams)
    }

    @Test
    fun `high strain sleep deficit finding suppresses sick indicator`() {
        val flags = setOf(RecoveryFlag.ILLNESS_ONSET)
        val finding =
            InsightFinding(
                InsightType.HIGH_STRAIN_SLEEP_DEFICIT,
                InsightParams.HighStrainSleepDeficit(strainRatio = 1.5f, sleepDeficitMinutes = 60),
            )
        val result = InsightDeriver.derive(flags, listOf(finding), dismissedTypes = emptySet())

        assertEquals(setOf(InsightType.HIGH_STRAIN_SLEEP_DEFICIT), result.active)
        assertEquals(InsightType.HIGH_STRAIN_SLEEP_DEFICIT, result.current)
        assertEquals(
            InsightParams.HighStrainSleepDeficit(strainRatio = 1.5f, sleepDeficitMinutes = 60),
            result.currentParams,
        )
    }

    @Test
    fun `load spike recovery strain finding suppresses workout impact`() {
        val flags = setOf(RecoveryFlag.WORKOUT_IMPACT)
        val finding =
            InsightFinding(
                InsightType.LOAD_SPIKE_RECOVERY_STRAIN,
                InsightParams.None,
            )
        val result = InsightDeriver.derive(flags, listOf(finding), dismissedTypes = emptySet())

        assertEquals(setOf(InsightType.LOAD_SPIKE_RECOVERY_STRAIN), result.active)
        assertEquals(listOf(InsightType.LOAD_SPIKE_RECOVERY_STRAIN), result.visibleQueue)
        assertEquals(InsightType.LOAD_SPIKE_RECOVERY_STRAIN, result.current)
    }

    @Test
    fun `late nadir short sleep finding suppresses late nadir`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED)
        val finding =
            InsightFinding(
                InsightType.LATE_NADIR_SHORT_SLEEP,
                InsightParams.LateNadirShortSleep(sleepDurationMinutes = 300, goalSleepMinutes = 480),
            )
        val result = InsightDeriver.derive(flags, listOf(finding), dismissedTypes = emptySet())

        assertEquals(setOf(InsightType.LATE_NADIR_SHORT_SLEEP), result.active)
        assertEquals(InsightType.LATE_NADIR_SHORT_SLEEP, result.current)
    }

    @Test
    fun `current insight without engine finding has no params`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED)
        val result = InsightDeriver.derive(flags, dismissedTypes = emptySet())

        assertEquals(InsightType.LATE_NADIR, result.current)
        assertEquals(InsightParams.None, result.currentParams)
    }

    @Test
    fun `HRV drop with low SpO2 finding suppresses sick indicator`() {
        val flags = setOf(RecoveryFlag.ILLNESS_ONSET)
        val finding =
            InsightFinding(
                InsightType.HRV_DROP_LOW_SPO2,
                InsightParams.HrvSpo2(zLnHrv = -1.8f, spo2 = 92f),
            )
        val result = InsightDeriver.derive(flags, listOf(finding), dismissedTypes = emptySet())

        assertEquals(setOf(InsightType.HRV_DROP_LOW_SPO2), result.active)
        assertEquals(InsightType.HRV_DROP_LOW_SPO2, result.current)
        assertEquals(InsightParams.HrvSpo2(zLnHrv = -1.8f, spo2 = 92f), result.currentParams)
    }

    @Test
    fun `late nadir elevated RHR finding suppresses late nadir`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED)
        val finding =
            InsightFinding(
                InsightType.LATE_NADIR_ELEVATED_RHR,
                InsightParams.LateNadirElevatedRhr(rhrDeltaBpm = 7f),
            )
        val result = InsightDeriver.derive(flags, listOf(finding), dismissedTypes = emptySet())

        assertEquals(setOf(InsightType.LATE_NADIR_ELEVATED_RHR), result.active)
        assertEquals(InsightType.LATE_NADIR_ELEVATED_RHR, result.current)
        assertEquals(InsightParams.LateNadirElevatedRhr(rhrDeltaBpm = 7f), result.currentParams)
    }
}
