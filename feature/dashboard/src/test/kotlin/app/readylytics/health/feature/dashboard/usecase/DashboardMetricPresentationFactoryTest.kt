package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.feature.dashboard.DashboardMetricUnavailableReason
import app.readylytics.health.feature.dashboard.DashboardMetricVisual
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardMetricPresentationFactoryTest {
    private lateinit var factory: DashboardMetricPresentationFactory
    private lateinit var resourceProvider: ResourceProvider
    private lateinit var getWorkoutMetricsUseCase: GetWorkoutMetricsUseCase

    @Before
    fun setup() {
        resourceProvider = mockk(relaxed = true)
        getWorkoutMetricsUseCase = mockk(relaxed = true)
        factory = DashboardMetricPresentationFactory(resourceProvider, getWorkoutMetricsUseCase)
        every { resourceProvider.getString(any()) } returns "mock_string"
        every { resourceProvider.getString(any(), any()) } returns "BMI mock_string"
        every { resourceProvider.getString(any(), any(), any()) } returns "BMI mock_string"
        // Task 10: DashboardMetricPresentationFactory now also calls getString with 3 and 4
        // vararg format args (e.g. semantics_score_format, semantics_weight_bmi_format) to build
        // real accessibilityDescription text; stub those arities too so the relaxed mock doesn't
        // fall through to an empty-string default for them.
        every { resourceProvider.getString(any(), any(), any(), any()) } returns "mock_string"
        every { resourceProvider.getString(any(), any(), any(), any(), any()) } returns "mock_string"
    }

    private fun summary(
        weightKg: Float? = null,
        bodyFatPercent: Float? = null,
        strainRatio: Float? = null,
    ) = DailySummary(
        date = date,
        weightKg = weightKg,
        bodyFatPercent = bodyFatPercent,
        strainRatioWorkoutOnly = strainRatio,
        strainRatioEverydayHr = strainRatio,
    )

    private fun preferences(
        heightCm: Float = 180f,
        gender: Gender = Gender.MALE,
        physiologyProfile: PhysiologyProfile = PhysiologyProfile.ACTIVE,
    ) = UserPreferences(
        heightCm = heightCm,
        gender = gender,
        physiologyProfile = physiologyProfile,
    )

    private val date = LocalDate.now()

    private val tooltipStubs =
        mapOf(
            CoreUiR.string.tooltip_sleep_score to "tooltip sleep score",
            CoreUiR.string.tooltip_readiness to "tooltip readiness",
            CoreUiR.string.card_tooltip_weight_no_data to "tooltip weight no data",
            CoreUiR.string.card_tooltip_weight_latest to "tooltip weight latest",
            CoreUiR.string.card_tooltip_body_fat_no_data to "tooltip body fat no data",
            CoreUiR.string.card_tooltip_body_fat_latest to "tooltip body fat latest",
            CoreUiR.string.card_tooltip_sleep_efficiency to "tooltip sleep efficiency",
            CoreUiR.string.tooltip_vitals_spo2 to "tooltip spo2",
            CoreUiR.string.card_tooltip_bp_no_data to "tooltip bp no data",
            CoreUiR.string.card_tooltip_bp_latest to "tooltip bp latest",
            DashboardR.string.tooltip_heart_rate_card to "tooltip heart rate",
            CoreUiR.string.tooltip_circadian_score to "tooltip circadian",
            CoreUiR.string.tooltip_strain_ratio to "tooltip strain ratio",
        )

    private fun stubTooltips() {
        tooltipStubs.forEach { (resourceId, text) ->
            every { resourceProvider.getString(resourceId) } returns text
        }
    }

    @Test
    fun `every card wires a real tooltip resource instead of an empty string`() {
        stubTooltips()

        val cards = factory.build(summary(), preferences(), date, null, null, null)

        val expected =
            mapOf(
                CardId.SLEEP_SCORE to "tooltip sleep score",
                CardId.READINESS to "tooltip readiness",
                CardId.WEIGHT to "tooltip weight no data",
                CardId.BODY_FAT to "tooltip body fat no data",
                CardId.SLEEP_EFFICIENCY to "tooltip sleep efficiency",
                CardId.OXYGEN_SATURATION to "tooltip spo2",
                CardId.BLOOD_PRESSURE to "tooltip bp no data",
                CardId.HEART_RATE to "tooltip heart rate",
                CardId.CIRCADIAN_CONSISTENCY to "tooltip circadian",
                CardId.STRAIN_RATIO to "tooltip strain ratio",
            )

        expected.forEach { (cardId, tooltip) ->
            val actual = cards.getValue(cardId).tooltip
            assertTrue("Expected a non-blank tooltip for $cardId", actual.isNotBlank())
            assertEquals("Unexpected tooltip for $cardId", tooltip, actual)
        }
    }

    @Test
    fun `weight body fat and blood pressure use their latest-reading tooltips once data exists`() {
        stubTooltips()

        val cards =
            factory.build(
                summary(weightKg = 70f, bodyFatPercent = 18f)
                    .copy(bloodPressureSystolic = 120, bloodPressureDiastolic = 80),
                preferences(),
                date,
                null,
                null,
                null,
            )

        assertEquals("tooltip weight latest", cards.getValue(CardId.WEIGHT).tooltip)
        assertEquals("tooltip body fat latest", cards.getValue(CardId.BODY_FAT).tooltip)
        assertEquals("tooltip bp latest", cards.getValue(CardId.BLOOD_PRESSURE).tooltip)
    }

    @Test
    fun `sleep score and readiness share score thresholds`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val sleep = cards.getValue(CardId.SLEEP_SCORE).visual as DashboardMetricVisual.Score
        val readiness = cards.getValue(CardId.READINESS).visual as DashboardMetricVisual.Score
        assertEquals(0f, sleep.minValue)
        assertEquals(100f, sleep.maxValue)
        assertEquals(0f, readiness.minValue)
        assertEquals(100f, readiness.maxValue)
    }

    @Test
    fun `score statuses preserve the legacy first-match boundaries and fallback`() {
        val expectations =
            listOf(
                40f to MetricStatus.POOR,
                60f to MetricStatus.WARNING,
                85f to MetricStatus.NEUTRAL,
                101f to MetricStatus.NEUTRAL,
            )

        expectations.forEach { (score, expectedStatus) ->
            val cards =
                factory.build(
                    summary().copy(sleepScore = score),
                    preferences(),
                    date,
                    null,
                    null,
                    null,
                )

            assertEquals(expectedStatus, cards.getValue(CardId.SLEEP_SCORE).status)
        }
    }

    @Test
    fun `readiness status uses the selected continuous score rather than its rounded display value`() {
        val cards =
            factory.build(
                summary().copy(readinessWorkoutOnly = 84.6f),
                preferences(),
                date,
                null,
                null,
                null,
            )

        val readiness = cards.getValue(CardId.READINESS)
        val visual = readiness.visual as DashboardMetricVisual.Score
        assertEquals(84.6f, visual.rawValue)
        assertEquals("85", readiness.valueText)
        assertEquals(MetricStatus.NEUTRAL, readiness.status)
    }

    @Test
    fun `weight keeps real value and positions its reference midpoint`() {
        val cards =
            factory.build(
                summary(weightKg = 66.44f),
                preferences(heightCm = 175f),
                date,
                null,
                null,
                null,
            )
        val card = cards.getValue(CardId.WEIGHT)
        val visual = card.visual as DashboardMetricVisual.ReferenceRange
        assertEquals(0.5f, visual.referenceMarkerFraction)
    }

    @Test
    fun `weight card displays only its value and unit without BMI`() {
        val cards =
            factory.build(
                summary(weightKg = 66.44f),
                preferences(heightCm = 175f),
                date,
                null,
                null,
                null,
            )

        val card = cards.getValue(CardId.WEIGHT)
        assertEquals("kg", card.unitText)
        assertNull(card.secondaryText)
    }

    @Test
    fun `body fat midpoint depends on profile and gender`() {
        val cards =
            factory.build(
                summary(bodyFatPercent = 9.5f),
                preferences(
                    gender = Gender.MALE,
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                ),
                date,
                null,
                null,
                null,
            )
        val visual = cards.getValue(CardId.BODY_FAT).visual as DashboardMetricVisual.ReferenceRange
        assertEquals(0.5f, visual.markerFraction)
    }

    @Test
    fun `sleep duration target uses goal sleep hours`() {
        val cards =
            factory.build(
                summary().copy(sleepDurationMinutes = 450),
                preferences().copy(goalSleepHours = 8f),
                date,
                null,
                null,
                null,
            )
        val visual = cards.getValue(CardId.SLEEP_DURATION).visual as DashboardMetricVisual.Goal
        assertEquals(480f, visual.targetValue)
        assertEquals(450f, visual.rawValue)
    }

    @Test
    fun `ras permits overflow beyond 100`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.RAS_DAILY).visual as DashboardMetricVisual.Score
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `circadian score bounds are 0 to 100`() {
        val circResult =
            app.readylytics.health.domain.scoring.CircadianConsistencyResult
                .Ready(85f, 0, 0, 0, 0)
        val cards = factory.build(summary(), preferences(), date, null, circResult, null)
        val visual = cards.getValue(CardId.CIRCADIAN_CONSISTENCY).visual as DashboardMetricVisual.Score
        assertEquals(0f, visual.minValue)
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `circadian consistency displays its rounded score as a percentage`() {
        val circResult =
            app.readylytics.health.domain.scoring.CircadianConsistencyResult
                .Ready(95.4f, 0, 0, 0, 0)
        every {
            resourceProvider.getString(
                DashboardR.string.semantics_score_format,
                "mock_string",
                "95",
                "100",
                "mock_string",
            )
        } returns "Circadian: 95 of 100, mock_string"

        val cards = factory.build(summary(), preferences(), date, null, circResult, null)
        val presentation = cards.getValue(CardId.CIRCADIAN_CONSISTENCY)
        val visual = presentation.visual as DashboardMetricVisual.Score

        assertEquals("95%", presentation.valueText)
        assertEquals(MetricStatus.NEUTRAL, presentation.status)
        assertEquals(95.4f, visual.rawValue)
        assertEquals("Circadian: 95 of 100, mock_string", presentation.accessibilityDescription)
    }

    @Test
    fun `sleep efficiency uses 0 to 100 bounds`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.SLEEP_EFFICIENCY).visual as DashboardMetricVisual.Score
        assertEquals(0f, visual.minValue)
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `sleep efficiency displays its stored percentage without scaling it again`() {
        val lastSleepSession = SleepSessionSummary(efficiency = 95.13f, startTime = 0L, endTime = 0L)
        every {
            resourceProvider.getString(
                DashboardR.string.semantics_value_note_format,
                "mock_string",
                "95%",
                "mock_string",
            )
        } returns "Sleep Efficiency: 95%, mock_string"

        val cards = factory.build(summary(), preferences(), date, lastSleepSession, null, null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as DashboardMetricVisual.Score

        assertEquals("95%", presentation.valueText)
        assertEquals("", presentation.unitText)
        assertEquals(95.13f, visual.rawValue)
        assertEquals("Sleep Efficiency: 95%, mock_string", presentation.accessibilityDescription)
    }

    @Test
    fun `sleep efficiency accepts legacy fractional values as percentages`() {
        val lastSleepSession = SleepSessionSummary(efficiency = 0.9f, startTime = 0L, endTime = 0L)

        val cards = factory.build(summary(), preferences(), date, lastSleepSession, null, null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as DashboardMetricVisual.Score

        assertEquals("90%", presentation.valueText)
        assertEquals("", presentation.unitText)
        assertEquals(MetricStatus.OPTIMAL, presentation.status)
        assertEquals(90f, visual.rawValue)
    }

    @Test
    fun `body fat bakes a one decimal percent into its value text`() {
        val cards =
            factory.build(
                summary(bodyFatPercent = 13.64f),
                preferences(),
                date,
                null,
                null,
                null,
            )
        val presentation = cards.getValue(CardId.BODY_FAT)

        // Sleep Efficiency's baked-in "%" pattern, but body fat keeps its decimal place.
        assertEquals("13.6%", presentation.valueText)
        assertEquals("", presentation.unitText)
    }

    @Test
    fun `missing body fat keeps the em dash without a stray percent sign`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val presentation = cards.getValue(CardId.BODY_FAT)

        assertEquals("\u2014", presentation.valueText)
        assertEquals("", presentation.unitText)
    }

    @Test
    fun `spo2 bakes its percent into the value text like sleep efficiency`() {
        val cards =
            factory.build(
                summary().copy(avgSleepingSpo2 = 96.4f),
                preferences(),
                date,
                null,
                null,
                null,
            )
        val presentation = cards.getValue(CardId.OXYGEN_SATURATION)

        assertEquals("96%", presentation.valueText)
        assertEquals("", presentation.unitText)
    }

    @Test
    fun `spo2 status uses the rounded legacy classification`() {
        val cards =
            factory.build(
                summary().copy(avgSleepingSpo2 = 94.6f),
                preferences(),
                date,
                null,
                null,
                null,
            )

        assertEquals(MetricStatus.OPTIMAL, cards.getValue(CardId.OXYGEN_SATURATION).status)
    }

    @Test
    fun `spo2 uses 80 to 100 bounds`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.OXYGEN_SATURATION).visual as DashboardMetricVisual.Score
        assertEquals(80f, visual.minValue)
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `hrv uses baseline scale`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.HRV).visual as DashboardMetricVisual.PersonalBaseline
        assertNull(visual.ratio)
    }

    @Test
    fun `rhr uses baseline scale`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.RESTING_HR).visual as DashboardMetricVisual.PersonalBaseline
        assertNull(visual.ratio)
    }

    @Test
    fun `strain ratio uses bands from 0 to 2`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.STRAIN_RATIO).visual as DashboardMetricVisual.Score
        assertEquals(0f, visual.minValue)
        assertEquals(2f, visual.maxValue)
    }

    @Test
    fun `strain ratio preserves the legacy first-match boundaries`() {
        val expectations =
            listOf(
                0.5f to MetricStatus.POOR,
                0.8f to MetricStatus.WARNING,
                1.3f to MetricStatus.OPTIMAL,
                1.5f to MetricStatus.WARNING,
                1.7f to MetricStatus.POOR,
            )

        expectations.forEach { (rawStrainRatio, expectedStatus) ->
            val cards = factory.build(summary(strainRatio = rawStrainRatio), preferences(), date, null, null, null)

            assertEquals(expectedStatus, cards.getValue(CardId.STRAIN_RATIO).status)
            assertNotEquals(MetricStatus.NEUTRAL, cards.getValue(CardId.STRAIN_RATIO).status)
        }
    }

    @Test
    fun `positive strain increase is formatted as an upward delta`() {
        every { resourceProvider.getString(CoreUiR.string.delta_up) } returns "↑"
        every { resourceProvider.getString(CoreUiR.string.delta_up_format, "↑", "0.23") } returns "↑ 0.23"

        val cards = factory.build(summary(), preferences(), date, null, null, null, 0.234f)

        assertEquals("↑ 0.23", cards.getValue(CardId.STRAIN_RATIO).secondaryText)
    }

    @Test
    fun `strain increase at the no-change threshold uses the no-change glyph`() {
        every { resourceProvider.getString(CoreUiR.string.delta_no_change) } returns "—"

        val cards = factory.build(summary(), preferences(), date, null, null, null, 0.005f)

        assertEquals("—", cards.getValue(CardId.STRAIN_RATIO).secondaryText)
    }

    @Test
    fun `unavailable strain increase has no secondary text`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null, null)

        assertNull(cards.getValue(CardId.STRAIN_RATIO).secondaryText)
    }

    @Test
    fun `heart rate and blood pressure are value only`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        assertTrue(cards.getValue(CardId.HEART_RATE).visual is DashboardMetricVisual.ValueOnly)
        assertTrue(cards.getValue(CardId.BLOOD_PRESSURE).visual is DashboardMetricVisual.ValueOnly)
    }

    @Test
    fun `heart rate displays its daily range and average`() {
        val heartRateSummary = HeartRateDaySummary(minBpm = 45, maxBpm = 147, avgBpm = 84)
        every { resourceProvider.getString(CoreUiR.string.hr_avg_display, 84) } returns "pulses · average 84"

        val cards = factory.build(summary(), preferences(), date, null, null, heartRateSummary)
        val presentation = cards.getValue(CardId.HEART_RATE)

        assertEquals("45–147", presentation.valueText)
        assertEquals("", presentation.unitText)
        assertEquals("pulses · average 84", presentation.secondaryText)
    }

    @Test
    fun `missing summary reports em dash and missing value for score based cards`() {
        val cards = factory.build(null, preferences(), date, null, null, null)

        val sleep = cards.getValue(CardId.SLEEP_SCORE)
        val sleepVisual = sleep.visual as DashboardMetricVisual.Score
        assertEquals("—", sleep.valueText)
        assertEquals(DashboardMetricUnavailableReason.MISSING_VALUE, sleepVisual.unavailableReason)
        assertNull(sleepVisual.markerFraction)

        val readiness = cards.getValue(CardId.READINESS)
        val readinessVisual = readiness.visual as DashboardMetricVisual.Score
        assertEquals("—", readiness.valueText)
        assertEquals(DashboardMetricUnavailableReason.MISSING_VALUE, readinessVisual.unavailableReason)
    }

    @Test
    fun `invalid height disables weight selection but keeps the real weight value`() {
        val zeroHeightCards =
            factory.build(
                summary(weightKg = 70f),
                preferences().copy(heightCm = 0f),
                date,
                null,
                null,
                null,
            )
        val zeroHeightVisual = zeroHeightCards.getValue(CardId.WEIGHT).visual as DashboardMetricVisual.ReferenceRange
        assertFalse(zeroHeightVisual.selectionAvailable)
        assertEquals(DashboardMetricUnavailableReason.MISSING_BMI, zeroHeightVisual.unavailableReason)
        assertNull(zeroHeightVisual.markerFraction)
        assertNotEquals("—", zeroHeightCards.getValue(CardId.WEIGHT).valueText)

        val nullHeightCards =
            factory.build(
                summary(weightKg = 70f),
                preferences().copy(heightCm = null),
                date,
                null,
                null,
                null,
            )
        val nullHeightVisual = nullHeightCards.getValue(CardId.WEIGHT).visual as DashboardMetricVisual.ReferenceRange
        assertFalse(nullHeightVisual.selectionAvailable)
        assertEquals(DashboardMetricUnavailableReason.MISSING_BMI, nullHeightVisual.unavailableReason)
    }

    @Test
    fun `sleep duration above goal clamps the marker but keeps the real minute count`() {
        val cards =
            factory.build(
                summary().copy(sleepDurationMinutes = 600),
                preferences().copy(goalSleepHours = 8f),
                date,
                null,
                null,
                null,
            )
        val visual = cards.getValue(CardId.SLEEP_DURATION).visual as DashboardMetricVisual.Goal
        assertEquals(600f, visual.rawValue)
        assertEquals(1f, visual.markerFraction)
        assertTrue(visual.isAboveTarget)
    }

    @Test
    fun `missing sleep session reports missing value for sleep efficiency instead of a zero reading`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as DashboardMetricVisual.Score
        assertEquals("—", presentation.valueText)
        assertEquals(DashboardMetricUnavailableReason.MISSING_VALUE, visual.unavailableReason)
        assertNull(visual.markerFraction)
    }

    @Test
    fun `a genuine zero efficiency reading is treated as real data not missing`() {
        val lastSleepSession = SleepSessionSummary(efficiency = 0f, startTime = 0L, endTime = 0L)
        val cards = factory.build(summary(), preferences(), date, lastSleepSession, null, null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as DashboardMetricVisual.Score
        assertEquals("0%", presentation.valueText)
        assertEquals("", presentation.unitText)
        assertNull(visual.unavailableReason)
        assertEquals(0f, visual.markerFraction)
    }

    // -------------------------------------------------------------------------
    // Task 10 (post-review): DashboardMetricPresentationFactory previously set
    // accessibilityDescription = "" unconditionally for every card. These regression-guard the
    // wiring for the card types DashboardMetricCardTest.kt's shell-level semantics fixtures cover
    // (Sleep Score, Sleep Duration, HRV, Weight, Body Fat) across both the unavailable branch
    // (all fields missing) and an available branch (real data), for both of which the description
    // must no longer be blank. Exact string content isn't asserted here — resourceProvider is a
    // relaxed mock returning fixed placeholders regardless of resId, so it can't distinguish real
    // resource wording; that would need either real resources (Robolectric, not currently a test
    // dependency of this module) or per-resId stubbing that re-derives the production format
    // strings in the test. See the Task 10 report for that as noted follow-up.
    // -------------------------------------------------------------------------

    @Test
    fun `all 15 cards produce a non-blank accessibility description when data is missing`() {
        val cards = factory.build(null, preferences(), date, null, null, null)

        val allCardIds =
            listOf(
                CardId.SLEEP_SCORE,
                CardId.READINESS,
                CardId.WEIGHT,
                CardId.BODY_FAT,
                CardId.SLEEP_DURATION,
                CardId.HRV,
                CardId.SLEEP_RHR,
                CardId.RESTING_HR,
                CardId.RAS_DAILY,
                CardId.SLEEP_EFFICIENCY,
                CardId.OXYGEN_SATURATION,
                CardId.BLOOD_PRESSURE,
                CardId.HEART_RATE,
                CardId.CIRCADIAN_CONSISTENCY,
                CardId.STRAIN_RATIO,
            )

        allCardIds.forEach { id ->
            val description = cards.getValue(id).accessibilityDescription
            assertTrue(
                "Expected non-blank accessibilityDescription for $id when data is missing",
                description.isNotBlank(),
            )
        }
    }

    @Test
    fun `all 15 cards produce a non-blank accessibility description when data is available`() {
        val lastSleepSession = SleepSessionSummary(efficiency = 0.88f, startTime = 0L, endTime = 0L)
        val circadianResult =
            app.readylytics.health.domain.scoring.CircadianConsistencyResult
                .Ready(85f, 0, 0, 0, 0)
        val heartRateSummary =
            app.readylytics.health.core.ui.model
                .HeartRateDaySummary(minBpm = 50, maxBpm = 130, avgBpm = 72)

        val cards =
            factory.build(
                summary(weightKg = 70f, bodyFatPercent = 20f).copy(
                    sleepScore = 85f,
                    readinessWorkoutOnly = 80f,
                    sleepDurationMinutes = 450,
                    nocturnalHrv = 55,
                    restingHeartRate = 60,
                    avgSleepingSpo2 = 98f,
                    bloodPressureSystolic = 120,
                    bloodPressureDiastolic = 80,
                ),
                preferences(heightCm = 180f),
                date,
                lastSleepSession,
                circadianResult,
                heartRateSummary,
            )

        val allCardIds =
            listOf(
                CardId.SLEEP_SCORE,
                CardId.READINESS,
                CardId.WEIGHT,
                CardId.BODY_FAT,
                CardId.SLEEP_DURATION,
                CardId.HRV,
                CardId.SLEEP_RHR,
                CardId.RESTING_HR,
                CardId.RAS_DAILY,
                CardId.SLEEP_EFFICIENCY,
                CardId.OXYGEN_SATURATION,
                CardId.BLOOD_PRESSURE,
                CardId.HEART_RATE,
                CardId.CIRCADIAN_CONSISTENCY,
                CardId.STRAIN_RATIO,
            )

        allCardIds.forEach { id ->
            val description = cards.getValue(id).accessibilityDescription
            assertTrue(
                "Expected non-blank accessibilityDescription for $id when data is available",
                description.isNotBlank(),
            )
        }
    }
}
