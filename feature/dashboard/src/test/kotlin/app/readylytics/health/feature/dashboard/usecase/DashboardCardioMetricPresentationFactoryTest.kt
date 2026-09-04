package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.util.ResourceProvider
import app.readylytics.health.core.scoring.domain.cardio.CooperNormsClassifier
import app.readylytics.health.core.scoring.domain.cardio.TrainingStressBalanceCalculator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardCardioMetricPresentationFactoryTest {
    private lateinit var resourceProvider: ResourceProvider
    private lateinit var factory: DashboardCardioMetricPresentationFactory

    private val preferences =
        UserPreferences(
            age = 30,
            gender = Gender.MALE,
        )

    @Before
    fun setUp() {
        resourceProvider = mockk(relaxed = true)
        factory =
            DashboardCardioMetricPresentationFactory(
                resourceProvider = resourceProvider,
                tsbCalculator = TrainingStressBalanceCalculator(),
                cooperClassifier = CooperNormsClassifier(),
            )

        every { resourceProvider.getString(DashboardR.string.card_title_cardio_fitness) } returns "Cardio Fitness"
        every { resourceProvider.getString(DashboardR.string.card_title_tsb) } returns "TSB"
        every { resourceProvider.getString(CoreUiR.string.unit_ml_kg_min) } returns "ml/kg/min"
        every { resourceProvider.getString(CoreUiR.string.tooltip_cardio_fitness) } returns
            "Estimated maximal oxygen uptake (VO2 Max), a key indicator of cardiorespiratory fitness."
        every { resourceProvider.getString(CoreUiR.string.tooltip_tsb) } returns "tooltip tsb"
        every { resourceProvider.getString(CoreUiR.string.metric_value_unavailable) } returns "--"
        every { resourceProvider.getString(CoreUiR.string.tooltip_cooper_norms_header) } returns
            "Cooper Institute Norms:"
        every { resourceProvider.getString(CoreUiR.string.tooltip_cooper_norms_header_profile, *anyVararg()) } answers {
            val args = it.invocation.args[1] as Array<*>
            "Cooper Institute Norms (${args[0]}, ${args[1]}):"
        }
        every { resourceProvider.getString(CoreUiR.string.tooltip_cardio_fitness_current, *anyVararg()) } answers {
            val args = it.invocation.args[1] as Array<*>
            "Current: ${args[0]}"
        }
        every { resourceProvider.getString(CoreUiR.string.format_age_years, *anyVararg()) } answers {
            val args = it.invocation.args[1] as Array<*>
            "${args[0]} yrs"
        }
        every { resourceProvider.getString(CoreUiR.string.cooper_category_superior) } returns "Superior"
        every { resourceProvider.getString(CoreUiR.string.cooper_category_excellent) } returns "Excellent"
        every { resourceProvider.getString(CoreUiR.string.cooper_category_good) } returns "Good"
        every { resourceProvider.getString(CoreUiR.string.cooper_category_fair) } returns "Fair"
        every { resourceProvider.getString(CoreUiR.string.cooper_category_poor) } returns "Poor"
        every { resourceProvider.getString(CoreUiR.string.vo2_max_source_label_wearable) } returns "Wearable"
        every { resourceProvider.getString(CoreUiR.string.vo2_max_source_label_estimated) } returns
            "Estimated (Resting HR)"
        every { resourceProvider.getString(CoreUiR.string.gender_male) } returns "Male"
        every { resourceProvider.getString(CoreUiR.string.gender_female) } returns "Female"
    }

    @Test
    fun cardioFitness_withData_hasUnitInSecondaryAndRichTooltip() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 7, 30),
                vo2Max = 51.0f,
                vo2MaxSource = "WEARABLE",
            )

        val result = factory.build(summary, preferences, "--")
        val cardio = result[CardId.CARDIO_FITNESS]

        assertNotNull(cardio)
        assertEquals("Cardio Fitness", cardio?.title)
        assertEquals("51", cardio?.valueText)
        assertEquals("", cardio?.unitText)
        assertEquals("ml/kg/min", cardio?.secondaryText)
        assertEquals(MetricStatus.OPTIMAL, cardio?.status)

        val tooltip = cardio?.tooltip.orEmpty()
        assertTrue("Tooltip should contain current classification", tooltip.contains("Current: Superior • Wearable"))
        assertTrue("Tooltip should contain profile header", tooltip.contains("Cooper Institute Norms (30 yrs, Male):"))
        assertTrue("Tooltip should contain superior band", tooltip.contains("• Superior: ≥ 50.5"))
        assertTrue("Tooltip should contain excellent band", tooltip.contains("• Excellent: 44.5–50.5"))
        assertTrue("Tooltip should contain good band", tooltip.contains("• Good: 40.5–44.5"))
        assertTrue("Tooltip should contain fair band", tooltip.contains("• Fair: 35.5–40.5"))
        assertTrue("Tooltip should contain poor band", tooltip.contains("• Poor: < 35.5"))
    }

    @Test
    fun cardioFitness_noData_hasUnitInSecondaryAndNormsInTooltip() {
        val summary = DailySummary(date = LocalDate.of(2026, 7, 30))

        val result = factory.build(summary, preferences, "--")
        val cardio = result[CardId.CARDIO_FITNESS]

        assertNotNull(cardio)
        assertEquals("Cardio Fitness", cardio?.title)
        assertEquals("--", cardio?.valueText)
        assertEquals("", cardio?.unitText)
        assertEquals("ml/kg/min", cardio?.secondaryText)
        assertEquals(MetricStatus.NO_DATA, cardio?.status)

        val tooltip = cardio?.tooltip.orEmpty()
        assertTrue("Tooltip should not contain current", !tooltip.contains("Current:"))
        assertTrue("Tooltip should contain profile header", tooltip.contains("Cooper Institute Norms (30 yrs, Male):"))
        assertTrue("Tooltip should contain superior band", tooltip.contains("• Superior: ≥ 50.5"))
    }

    @Test
    fun tsb_withPositiveValue_hasTitlePrefixAndTooltip() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 7, 30),
                ctlWorkoutOnly = 60f,
                atlWorkoutOnly = 45f,
            )

        val result = factory.build(summary, preferences, "--")
        val tsb = result[CardId.TSB]

        assertNotNull(tsb)
        assertEquals("TSB", tsb?.title)
        assertEquals("+15", tsb?.valueText)
        assertEquals("", tsb?.unitText)
        assertEquals("tooltip tsb", tsb?.tooltip)
        assertEquals(MetricStatus.OPTIMAL, tsb?.status)
    }

    @Test
    fun tsb_withNegativeValue_hasNegativeFormatting() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 7, 30),
                ctlWorkoutOnly = 30f,
                atlWorkoutOnly = 50f,
            )

        val result = factory.build(summary, preferences, "--")
        val tsb = result[CardId.TSB]

        assertNotNull(tsb)
        assertEquals("TSB", tsb?.title)
        assertEquals("-20", tsb?.valueText)
        assertEquals("", tsb?.unitText)
        assertEquals("tooltip tsb", tsb?.tooltip)
        assertEquals(MetricStatus.WARNING, tsb?.status)
    }

    @Test
    fun tsb_noData_showsUnavailable() {
        val summary = DailySummary(date = LocalDate.of(2026, 7, 30))

        val result = factory.build(summary, preferences, "--")
        val tsb = result[CardId.TSB]

        assertNotNull(tsb)
        assertEquals("TSB", tsb?.title)
        assertEquals("--", tsb?.valueText)
        assertEquals("tooltip tsb", tsb?.tooltip)
        assertEquals(MetricStatus.CALIBRATING, tsb?.status)
    }
}
