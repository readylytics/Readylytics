package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardVitalsMetricPresentationFactoryTest {
    private lateinit var resourceProvider: ResourceProvider
    private lateinit var factory: DashboardVitalsMetricPresentationFactory

    @Before
    fun setup() {
        resourceProvider = mockk(relaxed = true)
        every { resourceProvider.getString(any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any(), any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any(), any(), any()) } returns "mock_str"

        every { resourceProvider.getString(DashboardR.string.card_title_oxygen_saturation) } returns "Oxygen Saturation"
        every { resourceProvider.getString(DashboardR.string.card_title_blood_pressure) } returns "Blood Pressure"
        every { resourceProvider.getString(DashboardR.string.card_title_body_temperature) } returns "Body Temperature"
        every { resourceProvider.getString(DashboardR.string.card_title_heart_rate) } returns "Heart Rate"

        every { resourceProvider.getString(CoreUiR.string.tooltip_vitals_spo2) } returns "tooltip spo2"
        every { resourceProvider.getString(CoreUiR.string.card_tooltip_bp_no_data) } returns "tooltip bp no data"
        every { resourceProvider.getString(CoreUiR.string.card_tooltip_bp_latest) } returns "tooltip bp latest"
        every { resourceProvider.getString(CoreUiR.string.tooltip_vitals_body_temperature) } returns "tooltip body temp"
        every { resourceProvider.getString(DashboardR.string.tooltip_heart_rate_card) } returns "tooltip heart rate"

        every { resourceProvider.getString(CoreUiR.string.unit_mmHg) } returns "mmHg"
        every { resourceProvider.getString(CoreUiR.string.unit_celsius) } returns "°C"
        every { resourceProvider.getString(CoreUiR.string.unit_fahrenheit) } returns "°F"
        every { resourceProvider.getString(CoreUiR.string.body_temperature_calibrating) } returns "Calibrating"
        every { resourceProvider.getString(CoreUiR.string.metric_value_unavailable) } returns "—"

        every { resourceProvider.getString(CoreUiR.string.hr_avg_display, 68) } returns "Avg 68 bpm"
        every { resourceProvider.getString(CoreUiR.string.hr_avg_display, 84) } returns "Avg 84 bpm"

        factory = DashboardVitalsMetricPresentationFactory(resourceProvider)
    }

    @Test
    fun `builds oxygen saturation and blood pressure cards with valid data`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                avgSleepingSpo2 = 98.2f,
                bloodPressureSystolic = 120,
                bloodPressureDiastolic = 80,
            )
        val preferences = UserPreferences(unitSystem = UnitSystem.METRIC)

        val cards =
            factory.build(
                summary = summary,
                preferences = preferences,
                unavailableValueText = "—",
            )

        val spo2 = cards[CardId.OXYGEN_SATURATION]
        assertNotNull(spo2)
        assertEquals("Oxygen Saturation", spo2?.title)
        assertEquals("98%", spo2?.valueText)
        assertEquals("", spo2?.unitText)
        assertNull(spo2?.secondaryText)
        assertEquals(MetricStatus.OPTIMAL, spo2?.status)
        assertEquals("tooltip spo2", spo2?.tooltip)
        val spo2Visual = spo2?.visual as UniversalMetricVisual.Score
        assertEquals(98.2f, spo2Visual.rawValue)
        assertEquals(80f, spo2Visual.minValue)
        assertEquals(100f, spo2Visual.maxValue)

        val bp = cards[CardId.BLOOD_PRESSURE]
        assertNotNull(bp)
        assertEquals("Blood Pressure", bp?.title)
        assertEquals("120/80", bp?.valueText)
        assertEquals("mmHg", bp?.unitText)
        assertNull(bp?.secondaryText)
        assertEquals(MetricStatus.OPTIMAL, bp?.status)
        assertEquals("tooltip bp latest", bp?.tooltip)
        assertTrue(bp?.visual is UniversalMetricVisual.ValueOnly)
    }

    @Test
    fun `builds body temperature and heart rate cards with valid data`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                avgSleepingBodyTemp = 36.6f,
            )
        val preferences = UserPreferences(unitSystem = UnitSystem.METRIC)
        val hrSummary = HeartRateDaySummary(minBpm = 50, maxBpm = 110, avgBpm = 68)

        val cards =
            factory.build(
                summary = summary,
                preferences = preferences,
                heartRateSummary = hrSummary,
                bodyTempBaseline = 36.4f,
                unavailableValueText = "—",
            )

        val temp = cards[CardId.BODY_TEMPERATURE]
        assertNotNull(temp)
        assertEquals("Body Temperature", temp?.title)
        assertEquals("36.6", temp?.valueText)
        assertEquals("°C", temp?.unitText)
        assertEquals("+0.2°", temp?.secondaryText)
        assertEquals(MetricStatus.NEUTRAL, temp?.status)
        assertEquals("tooltip body temp", temp?.tooltip)
        val tempVisual = temp?.visual as UniversalMetricVisual.Score
        assertEquals(36.6f, tempVisual.rawValue!!, 0.01f)
        assertEquals(35.5f, tempVisual.minValue, 0.01f)
        assertEquals(39f, tempVisual.maxValue, 0.01f)

        val hr = cards[CardId.HEART_RATE]
        assertNotNull(hr)
        assertEquals("Heart Rate", hr?.title)
        assertEquals("50–110", hr?.valueText)
        assertEquals("", hr?.unitText)
        assertEquals("Avg 68 bpm", hr?.secondaryText)
        assertEquals(MetricStatus.NEUTRAL, hr?.status)
        assertEquals("tooltip heart rate", hr?.tooltip)
        assertTrue(hr?.visual is UniversalMetricVisual.ValueOnly)
    }

    @Test
    fun `builds cards with null summary and null heart rate`() {
        val preferences = UserPreferences(unitSystem = UnitSystem.METRIC)

        val cards =
            factory.build(
                summary = null,
                preferences = preferences,
                heartRateSummary = null,
                bodyTempBaseline = null,
                unavailableValueText = "—",
            )

        val spo2 = cards[CardId.OXYGEN_SATURATION]
        assertNotNull(spo2)
        assertEquals("—", spo2?.valueText)
        assertEquals(MetricStatus.CALIBRATING, spo2?.status)
        val spo2Visual = spo2?.visual as UniversalMetricVisual.Score
        assertNull(spo2Visual.rawValue)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, spo2Visual.unavailableReason)

        val bp = cards[CardId.BLOOD_PRESSURE]
        assertNotNull(bp)
        assertEquals("—", bp?.valueText)
        assertEquals("mmHg", bp?.unitText)
        assertEquals(MetricStatus.NEUTRAL, bp?.status)
        assertEquals("tooltip bp no data", bp?.tooltip)
        assertTrue(bp?.visual is UniversalMetricVisual.ValueOnly)

        val temp = cards[CardId.BODY_TEMPERATURE]
        assertNotNull(temp)
        assertEquals("—", temp?.valueText)
        assertEquals("°C", temp?.unitText)
        assertNull(temp?.secondaryText)
        assertEquals(MetricStatus.CALIBRATING, temp?.status)
        val tempVisual = temp?.visual as UniversalMetricVisual.Score
        assertNull(tempVisual.rawValue)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, tempVisual.unavailableReason)

        val hr = cards[CardId.HEART_RATE]
        assertNotNull(hr)
        assertEquals("—", hr?.valueText)
        assertNull(hr?.secondaryText)
        assertEquals(MetricStatus.CALIBRATING, hr?.status)
        assertTrue(hr?.visual is UniversalMetricVisual.ValueOnly)
    }

    @Test
    fun `body temperature in imperial unit system with calibrating baseline`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                avgSleepingBodyTemp = 37.0f,
            )
        val preferences = UserPreferences(unitSystem = UnitSystem.IMPERIAL)

        val cards =
            factory.build(
                summary = summary,
                preferences = preferences,
                bodyTempBaseline = null,
                unavailableValueText = "—",
            )

        val temp = cards[CardId.BODY_TEMPERATURE]
        assertNotNull(temp)
        assertEquals("98.6", temp?.valueText)
        assertEquals("°F", temp?.unitText)
        assertEquals("Calibrating", temp?.secondaryText)
        assertEquals(MetricStatus.NEUTRAL, temp?.status)
        val tempVisual = temp?.visual as UniversalMetricVisual.Score
        assertEquals(98.6f, tempVisual.rawValue!!, 0.01f)
        assertEquals(95.9f, tempVisual.minValue, 0.01f)
        assertEquals(102.2f, tempVisual.maxValue, 0.01f)
    }

    @Test
    fun `body temperature negative delta formatting`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                avgSleepingBodyTemp = 36.2f,
            )
        val preferences = UserPreferences(unitSystem = UnitSystem.METRIC)

        val cards =
            factory.build(
                summary = summary,
                preferences = preferences,
                bodyTempBaseline = 36.5f,
                unavailableValueText = "—",
            )

        val temp = cards[CardId.BODY_TEMPERATURE]
        assertNotNull(temp)
        assertEquals("-0.3°", temp?.secondaryText)
    }

    @Test
    fun `body temperature warning status when deviation exceeds threshold`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                avgSleepingBodyTemp = 38.0f,
            )
        val preferences = UserPreferences(bodyTempElevatedThresholdCelsius = 1.0f)

        val cards =
            factory.build(
                summary = summary,
                preferences = preferences,
                bodyTempBaseline = 36.9f,
            )

        val temp = cards[CardId.BODY_TEMPERATURE]
        assertNotNull(temp)
        assertEquals(MetricStatus.WARNING, temp?.status)
    }
}
