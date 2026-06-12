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
    ) = InsightContext(
        today = dailySummary(stepCount = stepCount),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
        stepGoal = stepGoal,
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
}
