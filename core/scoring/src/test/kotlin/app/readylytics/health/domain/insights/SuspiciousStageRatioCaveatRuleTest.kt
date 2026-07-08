package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuspiciousStageRatioCaveatRuleTest {
    private val rule = SuspiciousStageRatioCaveatRule()

    private fun context(recoveryFlags: Set<RecoveryFlag>) =
        InsightContext(
            today = dailySummary(recoveryFlags = recoveryFlags),
            circadianResult = CircadianConsistencyResult.MissingData,
            goalSleepMinutes = 480,
        )

    @Test
    fun `fires when SUSPICIOUS_STAGE_RATIO flag is present`() {
        val finding = rule.evaluate(context(setOf(RecoveryFlag.SUSPICIOUS_STAGE_RATIO)))

        assertEquals(InsightType.RECOVERY_SUSPICIOUS_STAGE_RATIO, finding?.type)
        assertEquals(InsightParams.None, finding?.params)
    }

    @Test
    fun `does not fire without SUSPICIOUS_STAGE_RATIO flag`() {
        assertNull(rule.evaluate(context(emptySet())))
    }

    @Test
    fun `does not fire for other recovery flags`() {
        assertNull(rule.evaluate(context(setOf(RecoveryFlag.HRV_MISSING, RecoveryFlag.CALIBRATING))))
    }
}
