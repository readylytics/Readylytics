package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepShortfallRuleTest {
    private val rule = StepShortfallRule()

    private fun context(
        stepCount: Int? = 5000,
        stepGoal: Int = 10000,
        circadianResult: CircadianConsistencyResult = CircadianConsistencyResult.MissingData,
        nowMinutesOfDay: Int = 1439,
    ) = InsightContext(
        today = dailySummary(stepCount = stepCount),
        circadianResult = circadianResult,
        goalSleepMinutes = 480,
        stepGoal = stepGoal,
        nowMinutesOfDay = nowMinutesOfDay,
    )

    @Test
    fun `fires when step count is well below the goal`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.STEP_SHORTFALL, finding?.type)
        assertEquals(InsightParams.StepShortfall(stepCount = 5000, stepGoal = 10000), finding?.params)
    }

    @Test
    fun `does not fire when stepCount is null`() {
        assertNull(rule.evaluate(context(stepCount = null)))
    }

    @Test
    fun `does not fire when stepGoal is zero`() {
        assertNull(rule.evaluate(context(stepGoal = 0)))
    }

    @Test
    fun `does not fire when stepGoal is negative`() {
        assertNull(rule.evaluate(context(stepGoal = -1)))
    }

    @Test
    fun `does not fire when step count is at the shortfall ratio boundary`() {
        // 10000 * 0.7 = 7000
        assertNull(rule.evaluate(context(stepCount = 7000)))
    }

    @Test
    fun `does not fire when step count is above the threshold`() {
        assertNull(rule.evaluate(context(stepCount = 9000)))
    }

    @Test
    fun `fires when step count is just below the threshold`() {
        val finding = rule.evaluate(context(stepCount = 6999))

        assertEquals(InsightType.STEP_SHORTFALL, finding?.type)
    }

    @Test
    fun `does not fire before the lead time window when circadian result is ready`() {
        // median bedtime 23:00 (1380), lead time 180 -> earliest 20:00 (1200); now 18:00 (1080)
        val finding =
            rule.evaluate(
                context(circadianResult = circadianReady(medianBedtimeMinutes = 1380), nowMinutesOfDay = 18 * 60),
            )

        assertNull(finding)
    }

    @Test
    fun `fires once within the lead time window when circadian result is ready`() {
        // median bedtime 23:00 (1380), lead time 180 -> earliest 20:00 (1200); now 21:00 (1260)
        val finding =
            rule.evaluate(
                context(circadianResult = circadianReady(medianBedtimeMinutes = 1380), nowMinutesOfDay = 21 * 60),
            )

        assertEquals(InsightType.STEP_SHORTFALL, finding?.type)
    }

    @Test
    fun `fires exactly at the lead time boundary`() {
        // median bedtime 23:00 (1380), lead time 180 -> earliest 20:00 (1200); now 20:00 (1200)
        val finding =
            rule.evaluate(
                context(circadianResult = circadianReady(medianBedtimeMinutes = 1380), nowMinutesOfDay = 20 * 60),
            )

        assertEquals(InsightType.STEP_SHORTFALL, finding?.type)
    }

    @Test
    fun `does not fire just after midnight with a median bedtime past midnight`() {
        // median bedtime 01:00 (normalized 25:00 = 1500), lead time 180 -> earliest 22:00 (1320);
        // now 00:30 (30) is before the window since "now" is not normalized
        val finding =
            rule.evaluate(
                context(circadianResult = circadianReady(medianBedtimeMinutes = 1500), nowMinutesOfDay = 30),
            )

        assertNull(finding)
    }

    @Test
    fun `does not fire before lead time even with a median bedtime past midnight`() {
        // median bedtime 01:00 (normalized 25:00 = 1500), lead time 180 -> earliest 22:00 (1320);
        // now 19:00 (1140) is before the window
        val finding =
            rule.evaluate(
                context(circadianResult = circadianReady(medianBedtimeMinutes = 1500), nowMinutesOfDay = 19 * 60),
            )

        assertNull(finding)
    }

    @Test
    fun `fires within the lead time window with a median bedtime past midnight`() {
        // median bedtime 01:00 (normalized 25:00 = 1500), lead time 180 -> earliest 22:00 (1320);
        // now 23:00 (1380) is within the window
        val finding =
            rule.evaluate(
                context(circadianResult = circadianReady(medianBedtimeMinutes = 1500), nowMinutesOfDay = 23 * 60),
            )

        assertEquals(InsightType.STEP_SHORTFALL, finding?.type)
    }
}
