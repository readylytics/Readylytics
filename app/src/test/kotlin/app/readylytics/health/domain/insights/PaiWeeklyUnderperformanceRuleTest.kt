package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PaiWeeklyUnderperformanceRuleTest {
    private val rule = PaiWeeklyUnderperformanceRule()
    private val today = LocalDate.of(2026, 6, 12)

    private fun context(
        todayTotalPai: Float? = 10f,
        recentDays: List<DailySummary> = emptyList(),
    ) = InsightContext(
        today = dailySummary(date = today, totalPai = todayTotalPai),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
        recentDays = recentDays,
    )

    @Test
    fun `fires when weekly PAI total is below target`() {
        val recent =
            (1..6).map { offset -> dailySummary(date = today.minusDays(offset.toLong()), totalPai = 10f) }
        // total = 10 (today) + 6*10 = 70 < 150
        val finding = rule.evaluate(context(recentDays = recent))

        assertEquals(InsightType.PAI_WEEKLY_UNDERPERFORMANCE, finding?.type)
        assertEquals(
            InsightParams.PaiWeeklyShortfall(weeklyPai = 70f, target = InsightConstants.PAI_WEEKLY_TARGET),
            finding?.params,
        )
    }

    @Test
    fun `does not fire when weekly PAI meets target`() {
        val recent =
            (1..6).map { offset -> dailySummary(date = today.minusDays(offset.toLong()), totalPai = 25f) }
        // total = 10 (today) + 6*25 = 160 >= 150
        assertNull(rule.evaluate(context(recentDays = recent)))
    }

    @Test
    fun `does not fire when weekly PAI equals target exactly`() {
        val recent =
            (1..6).map { offset -> dailySummary(date = today.minusDays(offset.toLong()), totalPai = 0f) }
        // 150 (today only) == target -> not below
        assertNull(rule.evaluate(context(todayTotalPai = 150f, recentDays = recent)))
    }

    @Test
    fun `does not fire when all days have null totalPai`() {
        val recent = (1..6).map { offset -> dailySummary(date = today.minusDays(offset.toLong())) }
        assertNull(rule.evaluate(context(todayTotalPai = null, recentDays = recent)))
    }

    @Test
    fun `treats null totalPai days as zero when at least one day has data`() {
        val recent =
            listOf(
                dailySummary(date = today.minusDays(1), totalPai = null),
                dailySummary(date = today.minusDays(2), totalPai = null),
            )
        val finding = rule.evaluate(context(todayTotalPai = 10f, recentDays = recent))

        assertEquals(InsightType.PAI_WEEKLY_UNDERPERFORMANCE, finding?.type)
        assertEquals(10f, (finding?.params as InsightParams.PaiWeeklyShortfall).weeklyPai)
    }

    @Test
    fun `deduplicates days by date when recentDays includes today`() {
        val recent = listOf(dailySummary(date = today, totalPai = 10f))
        val finding = rule.evaluate(context(todayTotalPai = 10f, recentDays = recent))

        assertEquals(InsightType.PAI_WEEKLY_UNDERPERFORMANCE, finding?.type)
        assertEquals(10f, (finding?.params as InsightParams.PaiWeeklyShortfall).weeklyPai)
    }
}
