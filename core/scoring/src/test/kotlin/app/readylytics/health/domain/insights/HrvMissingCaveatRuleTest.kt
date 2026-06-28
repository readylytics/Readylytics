package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrvMissingCaveatRuleTest {
    private val rule = HrvMissingCaveatRule()

    private fun context(recoveryFlags: Set<RecoveryFlag>) =
        InsightContext(
            today = dailySummary(recoveryFlags = recoveryFlags),
            circadianResult = CircadianConsistencyResult.MissingData,
            goalSleepMinutes = 480,
        )

    @Test
    fun `fires when HRV_MISSING flag is present`() {
        val finding = rule.evaluate(context(setOf(RecoveryFlag.HRV_MISSING)))

        assertEquals(InsightType.RECOVERY_HRV_MISSING, finding?.type)
        assertEquals(InsightParams.None, finding?.params)
    }

    @Test
    fun `does not fire without HRV_MISSING flag`() {
        assertNull(rule.evaluate(context(emptySet())))
    }

    @Test
    fun `does not fire for other recovery flags`() {
        assertNull(rule.evaluate(context(setOf(RecoveryFlag.STAGES_MISSING, RecoveryFlag.CALIBRATING))))
    }
}
