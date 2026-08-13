package app.readylytics.health.feature.dashboard.usecase
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
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
import kotlin.math.ln
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
        every { resourceProvider.getString(DashboardR.string.card_title_ras_daily) } returns "RAS"
        every { resourceProvider.getString(CoreUiR.string.metric_status_warning) } returns "Warning"
        every { resourceProvider.getString(CoreUiR.string.metric_status_neutral) } returns "Neutral"
        every { resourceProvider.getString(CoreUiR.string.metric_status_optimal) } returns "Optimal"
        every { resourceProvider.getString(CoreUiR.string.delta_up) } returns "↑"
        every { resourceProvider.getString(CoreUiR.string.delta_down) } returns "↓"
        every { resourceProvider.getString(CoreUiR.string.delta_no_change) } returns "—"
        every { resourceProvider.getString(CoreUiR.string.unit_ms) } returns "ms"
        every { resourceProvider.getString(CoreUiR.string.unit_bpm) } returns "bpm"
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
                rhrBpm = 60f,
                baselineCalculatedAtDate = date,
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
    fun `HRV equal to its personal baseline renders at 50 percent progress`() {
        val cards =
            buildCards(
                baseSummary.copy(
                    nocturnalHrv = 50,
                    hrvMuMssd = ln(50.0).toFloat(),
                    isCalibrating = false,
                ),
                preferences,
                null,
            )

        val visual = cards.getValue(CardId.HRV).visual as UniversalMetricVisual.PersonalBaseline

        assertEquals(0.5f, visual.markerFraction!!, 0.001f)
        assertEquals(0.5f, visual.baselineMarkerFraction, 0.001f)
    }

    @Test
    fun `HRV and RHR preserve their existing baseline delta indicators`() {
        every {
            resourceProvider.getString(CoreUiR.string.delta_up_format, "↑", "5 ms")
        } returns "↑ 5 ms"
        every {
            resourceProvider.getString(CoreUiR.string.delta_up_format, "↓", "5 bpm")
        } returns "↓ 5 bpm"

        val cards =
            buildCards(
                baseSummary.copy(
                    nocturnalHrv = 55,
                    hrvMuMssd = ln(50.0).toFloat(),
                    restingHeartRate = 60,
                    rhrBpm = 65f,
                    baselineCalculatedAtDate = date,
                    isCalibrating = false,
                ),
                preferences,
                null,
            )

        assertEquals("↑ 5 ms", cards.getValue(CardId.HRV).secondaryText)
        assertEquals("↓ 5 bpm", cards.getValue(CardId.SLEEP_RHR).secondaryText)
        assertEquals("↓ 5 bpm", cards.getValue(CardId.RESTING_HR).secondaryText)
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
        every {
            resourceProvider.getString(
                DashboardR.string.sleep_session_time_range_format,
                "22:51",
                "06:02",
            )
        } returns "22:51 → 06:02"

        val cards = buildCards(baseSummary.copy(sleepDurationMinutes = 431), preferences, session)

        assertEquals(
            "22:51 → 06:02",
            cards.getValue(CardId.SLEEP_DURATION).secondaryText,
        )
        assertTrue(cards.getValue(CardId.SLEEP_DURATION).tooltip.isNotBlank())
        assertTrue(cards.getValue(CardId.HRV).tooltip.isNotBlank())
        assertTrue(cards.getValue(CardId.RESTING_HR).tooltip.isNotBlank())
        assertTrue(cards.getValue(CardId.RAS_DAILY).tooltip.isNotBlank())
    }

    @Test
    fun `RAS accessibility classification matches rendering status at boundaries`() {
        data class Case(
            val value: Int,
            val expectedStatus: MetricStatus,
            val expectedClassification: String,
            val expectedDescription: String,
        )

        val cases =
            listOf(
                Case(50, MetricStatus.WARNING, "Warning", "RAS, 50 of 100, Warning"),
                Case(75, MetricStatus.NEUTRAL, "Neutral", "RAS, 75 of 100, Neutral"),
                Case(80, MetricStatus.NEUTRAL, "Neutral", "RAS, 80 of 100, Neutral"),
                Case(100, MetricStatus.OPTIMAL, "Optimal", "RAS, 100 of 100, Optimal"),
            )

        cases.forEach { case ->
            every {
                resourceProvider.getString(
                    DashboardR.string.semantics_score_format,
                    "RAS",
                    case.value.toString(),
                    "100",
                    case.expectedClassification,
                )
            } returns case.expectedDescription

            val card =
                buildCards(
                    baseSummary.copy(totalRasWorkoutOnly = case.value.toFloat()),
                    preferences,
                    null,
                ).getValue(CardId.RAS_DAILY)

            assertEquals("RAS ${case.value} rendering status", case.expectedStatus, card.status)
            assertEquals(
                "RAS ${case.value} accessibility classification",
                case.expectedDescription,
                card.accessibilityDescription,
            )
        }
    }

    private fun buildCards(
        summary: DailySummary?,
        preferences: UserPreferences,
        session: SleepSessionSummary?,
    ) = factory.build(summary, preferences, date, session, null, null)
}
