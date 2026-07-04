package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HrvDeclineStreakRuleTest {
    private val rule = HrvDeclineStreakRule()
    private val today = LocalDate.of(2026, 6, 12)

    private fun context(
        todayZLnHrv: Float? = -0.5f,
        recentDays: List<DailySummary> = defaultRecentDays(),
    ) = InsightContext(
        today = dailySummary(date = today, zLnHrv = todayZLnHrv),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
        recentDays = recentDays,
    )

    private fun defaultRecentDays(): List<DailySummary> =
        listOf(
            dailySummary(date = today.minusDays(1), zLnHrv = -0.3f),
            dailySummary(date = today.minusDays(2), zLnHrv = -0.8f),
            dailySummary(date = today.minusDays(3), zLnHrv = 0.5f),
        )

    @Test
    fun `fires when most recent N days all have negative zLnHrv`() {
        val finding = rule.evaluate(context())

        assertEquals(InsightType.HRV_DECLINE_STREAK, finding?.type)
        assertEquals(InsightParams.HrvDeclineStreak(days = InsightConstants.HRV_DECLINE_STREAK_DAYS), finding?.params)
    }

    @Test
    fun `does not fire when fewer than required days available`() {
        assertNull(rule.evaluate(context(recentDays = defaultRecentDays().take(1))))
    }

    @Test
    fun `does not fire when any of the recent days has null zLnHrv`() {
        val recent =
            listOf(
                dailySummary(date = today.minusDays(1), zLnHrv = null),
                dailySummary(date = today.minusDays(2), zLnHrv = -0.8f),
            )
        assertNull(rule.evaluate(context(recentDays = recent)))
    }

    @Test
    fun `does not fire when today zLnHrv is null`() {
        assertNull(rule.evaluate(context(todayZLnHrv = null)))
    }

    @Test
    fun `does not fire when not all recent days are below baseline`() {
        val recent =
            listOf(
                dailySummary(date = today.minusDays(1), zLnHrv = -0.3f),
                dailySummary(date = today.minusDays(2), zLnHrv = 0.1f),
            )
        assertNull(rule.evaluate(context(recentDays = recent)))
    }

    @Test
    fun `does not fire when zLnHrv is exactly zero on a day`() {
        val recent =
            listOf(
                dailySummary(date = today.minusDays(1), zLnHrv = 0f),
                dailySummary(date = today.minusDays(2), zLnHrv = -0.8f),
            )
        assertNull(rule.evaluate(context(recentDays = recent)))
    }

    @Test
    fun `duplicates by date in recentDays do not affect the streak length`() {
        val recent =
            listOf(
                dailySummary(date = today, zLnHrv = -0.5f),
                dailySummary(date = today.minusDays(1), zLnHrv = -0.3f),
                dailySummary(date = today.minusDays(2), zLnHrv = -0.8f),
            )
        val finding = rule.evaluate(context(recentDays = recent))

        assertEquals(InsightType.HRV_DECLINE_STREAK, finding?.type)
    }
}
