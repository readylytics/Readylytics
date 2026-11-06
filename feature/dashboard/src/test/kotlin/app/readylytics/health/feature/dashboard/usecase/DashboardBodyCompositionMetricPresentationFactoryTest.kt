package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardBodyCompositionMetricPresentationFactoryTest {
    private lateinit var resourceProvider: ResourceProvider
    private lateinit var factory: DashboardBodyCompositionMetricPresentationFactory

    @Before
    fun setup() {
        resourceProvider = mockk(relaxed = true)
        every { resourceProvider.getString(any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any(), any()) } returns "mock_str"
        every { resourceProvider.getString(any(), any(), any(), any()) } returns "mock_str"
        every { resourceProvider.getString(DashboardR.string.card_title_weight) } returns "Weight"
        every { resourceProvider.getString(DashboardR.string.card_title_body_fat) } returns "Body Fat"
        every { resourceProvider.getString(CoreUiR.string.unit_kg) } returns "kg"
        every { resourceProvider.getString(CoreUiR.string.unit_lbs) } returns "lbs"
        every { resourceProvider.getString(CoreUiR.string.card_tooltip_weight_no_data) } returns
            "tooltip weight no data"
        every { resourceProvider.getString(CoreUiR.string.card_tooltip_weight_latest) } returns "tooltip weight latest"
        every { resourceProvider.getString(CoreUiR.string.card_tooltip_body_fat_no_data) } returns
            "tooltip body fat no data"
        every { resourceProvider.getString(CoreUiR.string.card_tooltip_body_fat_latest) } returns
            "tooltip body fat latest"
        every { resourceProvider.getString(CoreUiR.string.metric_status_optimal) } returns "Optimal"
        every { resourceProvider.getString(CoreUiR.string.metric_status_neutral) } returns "Neutral"
        every { resourceProvider.getString(CoreUiR.string.metric_status_warning) } returns "Warning"
        every { resourceProvider.getString(CoreUiR.string.metric_status_poor) } returns "Poor"
        every { resourceProvider.getString(CoreUiR.string.metric_status_calibrating) } returns "Calibrating"
        every { resourceProvider.getString(CoreUiR.string.metric_unavailable_missing_bmi) } returns "Missing height"

        factory = DashboardBodyCompositionMetricPresentationFactory(resourceProvider)
    }

    @Test
    fun `builds weight and body fat cards in metric unit system`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                weightKg = 70f,
                bodyFatPercent = 15f,
            )
        val preferences =
            UserPreferences(
                unitSystem = UnitSystem.METRIC,
                heightCm = 175f,
                gender = Gender.MALE,
                physiologyProfile = PhysiologyProfile.ACTIVE,
            )

        val cards = factory.build(summary, preferences, unavailableValueText = "—")

        val weight = cards[CardId.WEIGHT]
        assertNotNull(weight)
        assertEquals("Weight", weight?.title)
        assertEquals("70.0", weight?.valueText)
        assertEquals("kg", weight?.unitText)
        assertEquals(MetricStatus.OPTIMAL, weight?.status)
        assertEquals("tooltip weight latest", weight?.tooltip)
        assertNull(weight?.secondaryText)
        val weightVisual = weight?.visual as UniversalMetricVisual.ReferenceRange
        assertEquals(true, weightVisual.selectionAvailable)
        assertNull(weightVisual.unavailableReason)

        val bodyFat = cards[CardId.BODY_FAT]
        assertNotNull(bodyFat)
        assertEquals("Body Fat", bodyFat?.title)
        assertEquals("15.0%", bodyFat?.valueText)
        assertEquals("", bodyFat?.unitText)
        assertEquals("tooltip body fat latest", bodyFat?.tooltip)
        assertNull(bodyFat?.secondaryText)
        val bodyFatVisual = bodyFat?.visual as UniversalMetricVisual.ReferenceRange
        assertEquals(true, bodyFatVisual.selectionAvailable)
    }

    @Test
    fun `builds weight card in imperial unit system`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                weightKg = 70f,
            )
        val preferences =
            UserPreferences(
                unitSystem = UnitSystem.IMPERIAL,
                heightCm = 175f,
            )

        val cards = factory.build(summary, preferences, unavailableValueText = "—")

        val weight = cards[CardId.WEIGHT]
        assertNotNull(weight)
        assertEquals("lbs", weight?.unitText)
        assertEquals("154.3", weight?.valueText)
    }

    @Test
    fun `builds cards with null summary and missing height`() {
        val preferences =
            UserPreferences(
                unitSystem = UnitSystem.METRIC,
                heightCm = null,
            )

        val cards = factory.build(summary = null, preferences = preferences, unavailableValueText = "—")

        val weight = cards[CardId.WEIGHT]
        assertNotNull(weight)
        assertEquals("—", weight?.valueText)
        assertEquals("tooltip weight no data", weight?.tooltip)
        assertEquals(MetricStatus.CALIBRATING, weight?.status)
        val weightVisual = weight?.visual as UniversalMetricVisual.ReferenceRange
        assertEquals(false, weightVisual.selectionAvailable)
        assertEquals(UniversalMetricUnavailableReason.MISSING_BMI, weightVisual.unavailableReason)

        val bodyFat = cards[CardId.BODY_FAT]
        assertNotNull(bodyFat)
        assertEquals("—", bodyFat?.valueText)
        assertEquals("tooltip body fat no data", bodyFat?.tooltip)
        assertEquals(MetricStatus.NEUTRAL, bodyFat?.status)
    }

    @Test
    fun `body fat reference range adapts to profile and gender`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 11, 5),
                bodyFatPercent = 9.5f,
            )
        val preferences =
            UserPreferences(
                gender = Gender.MALE,
                physiologyProfile = PhysiologyProfile.ATHLETE,
            )

        val cards = factory.build(summary, preferences)

        val bodyFat = cards[CardId.BODY_FAT]
        assertNotNull(bodyFat)
        val visual = bodyFat?.visual as UniversalMetricVisual.ReferenceRange
        assertEquals(0.5f, visual.markerFraction)
    }
}
