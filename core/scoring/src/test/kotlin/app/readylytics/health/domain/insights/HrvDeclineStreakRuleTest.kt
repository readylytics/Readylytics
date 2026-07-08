package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import kotlin.math.ln

class HrvDeclineStreakRuleTest {
    private val rule = HrvDeclineStreakRule()
    private val today = LocalDate.of(2026, 6, 12)

    // A baseline of 50ms, shared by every fixture below so "below baseline" days can be
    // expressed simply as nocturnalHrv values that round under 50.
    private val baselineMuLnHrv = ln(50f)

    private fun context(
        todayZLnHrv: Float? = -0.5f,
        todayNocturnalHrv: Int? = 45,
        recentDays: List<DailySummary> = defaultRecentDays(),
    ) = InsightContext(
        today =
            dailySummary(
                date = today,
                zLnHrv = todayZLnHrv,
                nocturnalHrv = todayNocturnalHrv,
                hrvMuMssd = baselineMuLnHrv,
            ),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
        recentDays = recentDays,
    )

    private fun defaultRecentDays(): List<DailySummary> =
        listOf(
            dailySummary(date = today.minusDays(1), zLnHrv = -0.3f, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
            dailySummary(date = today.minusDays(2), zLnHrv = -0.8f, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
            dailySummary(date = today.minusDays(3), zLnHrv = 0.5f, nocturnalHrv = 52, hrvMuMssd = baselineMuLnHrv),
        )

    @Test
    fun `fires when most recent N days all have negative zLnHrv and rounded HRV below rounded baseline`() {
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
                dailySummary(date = today.minusDays(1), zLnHrv = null, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
                dailySummary(date = today.minusDays(2), zLnHrv = -0.8f, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
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
                dailySummary(date = today.minusDays(1), zLnHrv = -0.3f, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
                dailySummary(date = today.minusDays(2), zLnHrv = 0.1f, nocturnalHrv = 51, hrvMuMssd = baselineMuLnHrv),
            )
        assertNull(rule.evaluate(context(recentDays = recent)))
    }

    @Test
    fun `does not fire when zLnHrv is exactly zero on a day`() {
        val recent =
            listOf(
                dailySummary(date = today.minusDays(1), zLnHrv = 0f, nocturnalHrv = 50, hrvMuMssd = baselineMuLnHrv),
                dailySummary(date = today.minusDays(2), zLnHrv = -0.8f, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
            )
        assertNull(rule.evaluate(context(recentDays = recent)))
    }

    @Test
    fun `does not fire when a day's zLnHrv is a hair below zero but its rounded HRV equals rounded baseline`() {
        // Reproduces the reported bug: float noise from the ln() pipeline can leave zLnHrv
        // very slightly negative even though the displayed HRV rounds to the same ms value
        // as the baseline (dashboard would show "on baseline", not "below").
        val todaySummary = dailySummary(date = today, zLnHrv = -0.0001f, nocturnalHrv = 50, hrvMuMssd = baselineMuLnHrv)
        val context =
            InsightContext(
                today = todaySummary,
                circadianResult = CircadianConsistencyResult.MissingData,
                goalSleepMinutes = 480,
                recentDays = defaultRecentDays(),
            )

        assertNull(rule.evaluate(context))
    }

    @Test
    fun `fires when a day is genuinely below both the zLnHrv and rounded-baseline thresholds`() {
        val recent =
            listOf(
                dailySummary(date = today.minusDays(1), zLnHrv = -0.3f, nocturnalHrv = 44, hrvMuMssd = baselineMuLnHrv),
                dailySummary(date = today.minusDays(2), zLnHrv = -0.8f, nocturnalHrv = 40, hrvMuMssd = baselineMuLnHrv),
            )
        val finding = rule.evaluate(context(recentDays = recent))

        assertEquals(InsightType.HRV_DECLINE_STREAK, finding?.type)
    }

    @Test
    fun `duplicates by date in recentDays do not affect the streak length`() {
        val recent =
            listOf(
                dailySummary(date = today, zLnHrv = -0.5f, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
                dailySummary(date = today.minusDays(1), zLnHrv = -0.3f, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
                dailySummary(date = today.minusDays(2), zLnHrv = -0.8f, nocturnalHrv = 45, hrvMuMssd = baselineMuLnHrv),
            )
        val finding = rule.evaluate(context(recentDays = recent))

        assertEquals(InsightType.HRV_DECLINE_STREAK, finding?.type)
    }
}
