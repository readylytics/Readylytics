package app.readylytics.health.domain.dashboard

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightDeriverTest {
    @Test
    fun `empty inputs return empty result`() {
        val result = InsightDeriver.derive(emptySet(), emptySet())
        assertTrue(result.active.isEmpty())
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `null flags are treated as empty`() {
        val result = InsightDeriver.derive(null, emptySet())
        assertTrue(result.active.isEmpty())
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `non-insight recovery flags are ignored`() {
        val flags = setOf(RecoveryFlag.CALIBRATING, RecoveryFlag.HRV_MISSING)
        val result = InsightDeriver.derive(flags, emptySet())
        assertTrue(result.active.isEmpty())
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `active insights without dismissals are queued in display priority`() {
        val flags = setOf(RecoveryFlag.OVERREACHING, RecoveryFlag.ILLNESS_ONSET, RecoveryFlag.NADIR_DELAYED)
        val result = InsightDeriver.derive(flags, emptySet())
        assertEquals(setOf(InsightType.LATE_NADIR, InsightType.SICK_INDICATOR, InsightType.OVERREACHING), result.active)
        assertEquals(
            listOf(InsightType.SICK_INDICATOR, InsightType.OVERREACHING, InsightType.LATE_NADIR),
            result.visibleQueue,
        )
        assertEquals(InsightType.SICK_INDICATOR, result.current)
        assertEquals(0, result.dismissedCount)
    }

    @Test
    fun `dismissing current insight rotates to next queued insight`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED, RecoveryFlag.ILLNESS_ONSET, RecoveryFlag.OVERREACHING)
        val dismissed = setOf(InsightType.SICK_INDICATOR)
        val result = InsightDeriver.derive(flags, dismissed)

        assertEquals(setOf(InsightType.LATE_NADIR, InsightType.SICK_INDICATOR, InsightType.OVERREACHING), result.active)
        assertEquals(listOf(InsightType.OVERREACHING, InsightType.LATE_NADIR), result.visibleQueue)
        assertEquals(InsightType.OVERREACHING, result.current)
        assertEquals(1, result.dismissedCount)
    }

    @Test
    fun `fully dismissed active insights leave rerun state`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED)
        val dismissed = setOf(InsightType.LATE_NADIR)
        val result = InsightDeriver.derive(flags, dismissed)

        assertEquals(setOf(InsightType.LATE_NADIR), result.active)
        assertTrue(result.visibleQueue.isEmpty())
        assertEquals(null, result.current)
        assertEquals(1, result.dismissedCount)
    }

    @Test
    fun `dismissals of non-active insights are ignored in count`() {
        val flags = setOf(RecoveryFlag.NADIR_DELAYED)
        val dismissed = setOf(InsightType.SICK_INDICATOR)
        val result = InsightDeriver.derive(flags, dismissed)

        assertEquals(setOf(InsightType.LATE_NADIR), result.active)
        assertEquals(listOf(InsightType.LATE_NADIR), result.visibleQueue)
        assertEquals(InsightType.LATE_NADIR, result.current)
        assertEquals(0, result.dismissedCount)
    }
}
