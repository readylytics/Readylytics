package app.readylytics.health.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InsightTypeTest {
    @Test
    fun `maps recovery flags to expected insight types`() {
        assertEquals(InsightType.LATE_NADIR, InsightType.fromRecoveryFlag(RecoveryFlag.NADIR_DELAYED))
        assertEquals(InsightType.SICK_INDICATOR, InsightType.fromRecoveryFlag(RecoveryFlag.ILLNESS_ONSET))
        assertEquals(
            InsightType.STRONG_RECOVERY_SIGNAL,
            InsightType.fromRecoveryFlag(RecoveryFlag.STRONG_RECOVERY_SIGNAL),
        )
    }

    @Test
    fun `returns null for non-insight recovery flags`() {
        assertNull(InsightType.fromRecoveryFlag(RecoveryFlag.OVERREACHING))
        assertNull(InsightType.fromRecoveryFlag(RecoveryFlag.CALIBRATING))
        assertNull(InsightType.fromRecoveryFlag(RecoveryFlag.HRV_MISSING))
        assertNull(InsightType.fromRecoveryFlag(RecoveryFlag.STAGES_MISSING))
    }

    @Test
    fun `exposes load spike recovery strain insight type`() {
        assertEquals(InsightType.LOAD_SPIKE_RECOVERY_STRAIN, InsightType.valueOf("LOAD_SPIKE_RECOVERY_STRAIN"))
    }
}
