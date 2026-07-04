package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CircadianShiftRecoveryMissRuleTest {
    private val rule = CircadianShiftRecoveryMissRule()

    @Test
    fun `fires when rest day had no impact and bedtime offset exceeds threshold`() {
        val context =
            InsightContext(
                today = dailySummary(recoveryFlags = setOf(RecoveryFlag.REST_DAY_NO_IMPACT)),
                circadianResult = circadianReady(latestBedtimeOffsetMinutes = 105),
                goalSleepMinutes = 480,
            )

        val finding = rule.evaluate(context)

        assertEquals(InsightType.CIRCADIAN_SHIFT_RECOVERY_MISS, finding?.type)
        assertEquals(InsightParams.CircadianShift(bedtimeOffsetMinutes = 105), finding?.params)
    }

    @Test
    fun `does not fire when offset is exactly at threshold`() {
        val context =
            InsightContext(
                today = dailySummary(recoveryFlags = setOf(RecoveryFlag.REST_DAY_NO_IMPACT)),
                circadianResult = circadianReady(latestBedtimeOffsetMinutes = 90),
                goalSleepMinutes = 480,
            )

        assertNull(rule.evaluate(context))
    }

    @Test
    fun `does not fire when REST_DAY_NO_IMPACT flag is absent`() {
        val context =
            InsightContext(
                today = dailySummary(recoveryFlags = emptySet()),
                circadianResult = circadianReady(latestBedtimeOffsetMinutes = 105),
                goalSleepMinutes = 480,
            )

        assertNull(rule.evaluate(context))
    }

    @Test
    fun `does not fire when circadian result is calibrating`() {
        val context =
            InsightContext(
                today = dailySummary(recoveryFlags = setOf(RecoveryFlag.REST_DAY_NO_IMPACT)),
                circadianResult = CircadianConsistencyResult.Calibrating,
                goalSleepMinutes = 480,
            )

        assertNull(rule.evaluate(context))
    }

    @Test
    fun `does not fire when circadian result is missing data`() {
        val context =
            InsightContext(
                today = dailySummary(recoveryFlags = setOf(RecoveryFlag.REST_DAY_NO_IMPACT)),
                circadianResult = CircadianConsistencyResult.MissingData,
                goalSleepMinutes = 480,
            )

        assertNull(rule.evaluate(context))
    }

    @Test
    fun `does not fire when bedtime was earlier than average`() {
        val context =
            InsightContext(
                today = dailySummary(recoveryFlags = setOf(RecoveryFlag.REST_DAY_NO_IMPACT)),
                circadianResult = circadianReady(latestBedtimeOffsetMinutes = -120),
                goalSleepMinutes = 480,
            )

        assertNull(rule.evaluate(context))
    }
}
