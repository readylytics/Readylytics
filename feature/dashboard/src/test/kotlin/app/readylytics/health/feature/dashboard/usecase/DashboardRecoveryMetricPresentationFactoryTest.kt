package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.util.ResourceProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardRecoveryMetricPresentationFactoryTest {
    private lateinit var factory: DashboardMetricPresentationFactory
    private lateinit var resourceProvider: ResourceProvider

    private val date = LocalDate.of(2026, 7, 30)
    private val baseSummary =
        DailySummary(
            date = date,
            sleepDurationMinutes = 450,
            nocturnalHrv = 55,
            hrvBaseline = 50,
            restingHeartRate = 60,
            restingHrRatio = 1f,
            isCalibrating = false,
            totalRasWorkoutOnly = 80f,
        )
    private val preferences =
        UserPreferences(
            goalSleepHours = 8f,
            hrvOptimalThreshold = 1.05f,
            hrvWarningThreshold = 0.9f,
            rhrOptimalThreshold = 0.95f,
            rhrWarningThreshold = 1.05f,
            rasSourceMode = LoadSourceMode.WORKOUT_ONLY,
        )

    @Before
    fun setUp() {
        resourceProvider = mockk(relaxed = true)
        factory =
            DashboardMetricPresentationFactory(
                resourceProvider,
                mockk<GetWorkoutMetricsUseCase>(relaxed = true),
            )

        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_duration, any())
        } returns "sleep-duration-tooltip"
        every { resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv) } returns "hrv-tooltip"
        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv_baseline, any(), any(), any())
        } returns "hrv-baseline"
        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv_baseline_no_today, any())
        } returns "hrv-baseline-no-today"
        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv_no_baseline)
        } returns "hrv-no-baseline"
        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv_diagnostics, any(), any())
        } returns "hrv-diagnostics"
        every { resourceProvider.getString(CoreUiR.string.tooltip_sleep_rhr) } returns "sleep-rhr-tooltip"
        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_rhr_baseline, any(), any(), any())
        } returns "sleep-rhr-baseline"
        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_rhr_no_baseline)
        } returns "sleep-rhr-no-baseline"
        every {
            resourceProvider.getString(DashboardR.string.tooltip_resting_hr_baseline, any(), any(), any())
        } returns "resting-hr-tooltip"
        every {
            resourceProvider.getString(DashboardR.string.tooltip_resting_hr_no_baseline)
        } returns "resting-hr-no-baseline"
        every { resourceProvider.getString(CoreUiR.string.tooltip_ras) } returns "ras-tooltip"
        every { resourceProvider.getString(any(), any(), any()) } returns "22:51 → 06:02"
    }

    @Test
    fun `recovery cards reuse original domain statuses`() {
        val summary =
            DailySummary(
                date = date,
                sleepDurationMinutes = 240,
                nocturnalHrv = 40,
                hrvBaseline = 80,
                restingHeartRate = 72,
                restingHrRatio = 1.2f,
                isCalibrating = false,
                totalRasWorkoutOnly = 60f,
            )

        val cards = buildCards(summary, preferences, null)

        assertEquals(MetricStatus.POOR, cards.getValue(CardId.SLEEP_DURATION).status)
        assertEquals(MetricStatus.POOR, cards.getValue(CardId.HRV).status)
        assertEquals(MetricStatus.POOR, cards.getValue(CardId.RESTING_HR).status)
        assertEquals(MetricStatus.WARNING, cards.getValue(CardId.RAS_DAILY).status)
    }

    @Test
    fun `sleep time range and original tooltip content are retained`() {
        val zone = ZoneId.systemDefault()
        val start =
            ZonedDateTime
                .of(2026, 7, 29, 22, 51, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
        val end =
            ZonedDateTime
                .of(2026, 7, 30, 6, 2, 0, 0, zone)
                .toInstant()
                .toEpochMilli()
        val session = SleepSessionSummary(0.9f, start, end)

        val cards = buildCards(baseSummary.copy(sleepDurationMinutes = 431), preferences, session)

        assertTrue(
            cards
                .getValue(CardId.SLEEP_DURATION)
                .secondaryText
                .orEmpty()
                .contains("→"),
        )
        assertTrue(cards.getValue(CardId.SLEEP_DURATION).tooltip.isNotBlank())
        assertTrue(cards.getValue(CardId.HRV).tooltip.isNotBlank())
        assertTrue(cards.getValue(CardId.RESTING_HR).tooltip.isNotBlank())
        assertTrue(cards.getValue(CardId.RAS_DAILY).tooltip.isNotBlank())
    }

    private fun buildCards(
        summary: DailySummary?,
        preferences: UserPreferences,
        session: SleepSessionSummary?,
    ) = factory.build(summary, preferences, date, session, null, null)
}
