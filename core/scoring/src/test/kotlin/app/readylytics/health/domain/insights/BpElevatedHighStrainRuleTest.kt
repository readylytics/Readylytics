package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BpElevatedHighStrainRuleTest {
    private val rule = BpElevatedHighStrainRule()
    private val today = LocalDate.of(2026, 6, 12)

    private val baselineDays =
        (1..3).map { offset ->
            dailySummary(date = today.minusDays(offset.toLong()), bloodPressureSystolic = 110)
        }

    private fun context(
        todaySystolic: Int? = 125,
        strainRatio: Float? = 1.5f,
        recentDays: List<DailySummary> = baselineDays,
    ) = InsightContext(
        today = dailySummary(date = today, bloodPressureSystolic = todaySystolic, strainRatio = strainRatio),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
        recentDays = recentDays,
    )

    @Test
    fun `fires when systolic drift exceeds threshold and strain ratio is high`() {
        // baseline = 110, today = 125, drift = 15 > 10; strain 1.5 > 1.3
        val finding = rule.evaluate(context())

        assertEquals(InsightType.BP_ELEVATED_HIGH_STRAIN, finding?.type)
        assertEquals(InsightParams.BpElevatedStrain(systolicDriftMmHg = 15, strainRatio = 1.5f), finding?.params)
    }

    @Test
    fun `does not fire when today systolic is null`() {
        assertNull(rule.evaluate(context(todaySystolic = null)))
    }

    @Test
    fun `does not fire with fewer than minimum baseline samples`() {
        assertNull(rule.evaluate(context(recentDays = baselineDays.take(2))))
    }

    @Test
    fun `does not fire when drift is at threshold`() {
        // baseline = 110, drift = 10 -> not > 10
        assertNull(rule.evaluate(context(todaySystolic = 120)))
    }

    @Test
    fun `does not fire when strain ratio is at threshold`() {
        assertNull(rule.evaluate(context(strainRatio = 1.3f)))
    }

    @Test
    fun `does not fire when strain ratio is null`() {
        assertNull(rule.evaluate(context(strainRatio = null)))
    }

    @Test
    fun `baseline excludes today and only uses up to 7 most recent days`() {
        val manyDays =
            (1..10).map { offset ->
                dailySummary(date = today.minusDays(offset.toLong()), bloodPressureSystolic = 110)
            }
        val finding = rule.evaluate(context(recentDays = manyDays))

        assertEquals(InsightType.BP_ELEVATED_HIGH_STRAIN, finding?.type)
    }
}
