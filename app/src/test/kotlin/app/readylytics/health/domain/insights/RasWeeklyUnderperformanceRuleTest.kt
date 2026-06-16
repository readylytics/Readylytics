package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RasWeeklyUnderperformanceRuleTest {
    private val rule = RasWeeklyUnderperformanceRule()
    private val today = LocalDate.of(2026, 6, 12)

    private fun context(
        todayTotalRas: Float? = 10f,
        recentDays: List<DailySummary> = emptyList(),
    ) = InsightContext(
        today = dailySummary(date = today, totalRas = todayTotalRas),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
        recentDays = recentDays,
    )

    @Test
    fun `fires when weekly RAS total is below target`() {
        val recent =
            (1..6).map { offset -> dailySummary(date = today.minusDays(offset.toLong()), totalRas = 10f) }
        // total = 10 (today) + 6*10 = 70 < 150
        val finding = rule.evaluate(context(recentDays = recent))

        assertEquals(InsightType.RAS_WEEKLY_UNDERPERFORMANCE, finding?.type)
        assertEquals(
            InsightParams.RasWeeklyShortfall(weeklyRas = 70f, target = InsightConstants.RAS_WEEKLY_TARGET),
            finding?.params,
        )
    }

    @Test
    fun `does not fire when weekly RAS meets target`() {
        val recent =
            (1..6).map { offset -> dailySummary(date = today.minusDays(offset.toLong()), totalRas = 25f) }
        // total = 10 (today) + 6*25 = 160 >= 150
        assertNull(rule.evaluate(context(recentDays = recent)))
    }

    @Test
    fun `does not fire when weekly RAS equals target exactly`() {
        val recent =
            (1..6).map { offset -> dailySummary(date = today.minusDays(offset.toLong()), totalRas = 0f) }
        // 150 (today only) == target -> not below
        assertNull(rule.evaluate(context(todayTotalRas = 150f, recentDays = recent)))
    }

    @Test
    fun `does not fire when all days have null totalRas`() {
        val recent = (1..6).map { offset -> dailySummary(date = today.minusDays(offset.toLong())) }
        assertNull(rule.evaluate(context(todayTotalRas = null, recentDays = recent)))
    }

    @Test
    fun `treats null totalRas days as zero when at least one day has data`() {
        val recent =
            listOf(
                dailySummary(date = today.minusDays(1), totalRas = null),
                dailySummary(date = today.minusDays(2), totalRas = null),
            )
        val finding = rule.evaluate(context(todayTotalRas = 10f, recentDays = recent))

        assertEquals(InsightType.RAS_WEEKLY_UNDERPERFORMANCE, finding?.type)
        assertEquals(10f, (finding?.params as InsightParams.RasWeeklyShortfall).weeklyRas)
    }

    @Test
    fun `deduplicates days by date when recentDays includes today`() {
        val recent = listOf(dailySummary(date = today, totalRas = 10f))
        val finding = rule.evaluate(context(todayTotalRas = 10f, recentDays = recent))

        assertEquals(InsightType.RAS_WEEKLY_UNDERPERFORMANCE, finding?.type)
        assertEquals(10f, (finding?.params as InsightParams.RasWeeklyShortfall).weeklyRas)
    }
}
