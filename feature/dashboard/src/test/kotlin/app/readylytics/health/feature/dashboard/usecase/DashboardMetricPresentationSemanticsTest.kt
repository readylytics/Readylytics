package app.readylytics.health.feature.dashboard.usecase
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.SleepSessionSummary
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.readylytics.health.core.ui.R as CoreUiR

class DashboardMetricPresentationSemanticsTest : DashboardMetricPresentationFactoryTestBase() {
    @Test
    fun `spo2 status uses the continuous raw value rather than its rounded display value`() {
        val cards =
            factory.build(
                summary().copy(avgSleepingSpo2 = 94.6f),
                preferences(),
                date,
                null,
                null,
                null,
            )

        assertEquals(MetricStatus.WARNING, cards.getValue(CardId.OXYGEN_SATURATION).status)
    }

    @Test
    fun `spo2 uses 80 to 100 bounds`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.OXYGEN_SATURATION).visual as UniversalMetricVisual.Score
        assertEquals(80f, visual.minValue)
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `hrv uses baseline scale`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.HRV).visual as UniversalMetricVisual.PersonalBaseline
        assertNull(visual.ratio)
    }

    @Test
    fun `hrv uses displayed rounded baseline for status at the 110 percent boundary`() {
        val cards =
            factory.build(
                summary().copy(
                    nocturnalHrv = 42,
                    hrvBaseline = 38,
                    hrvMuMssd = kotlin.math.ln(41.0).toFloat(),
                ),
                preferences(),
                date,
                null,
                null,
                null,
            )

        val visual = cards.getValue(CardId.HRV).visual as UniversalMetricVisual.PersonalBaseline
        assertEquals(41f, visual.baselineValue)
        assertEquals(42f / 41f, visual.ratio!!)
        assertEquals(MetricStatus.NEUTRAL, cards.getValue(CardId.HRV).status)
    }

    @Test
    fun `rhr uses baseline scale`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.RESTING_HR).visual as UniversalMetricVisual.PersonalBaseline
        assertNull(visual.ratio)
    }

    @Test
    fun `rhr visual uses rounded override baseline instead of raw baseline`() {
        val cards =
            factory.build(
                summary().copy(
                    restingHeartRate = 62,
                    restingHrRatio = 1.11f,
                ),
                preferences().copy(rhrBaselineOverride = 55.6f),
                date,
                null,
                null,
                null,
            )

        val sleepVisual = cards.getValue(CardId.SLEEP_RHR).visual as UniversalMetricVisual.PersonalBaseline
        val restingVisual = cards.getValue(CardId.RESTING_HR).visual as UniversalMetricVisual.PersonalBaseline

        assertEquals(56f, sleepVisual.baselineValue)
        assertEquals(62f / 56f, sleepVisual.ratio!!)
        assertEquals(56f, restingVisual.baselineValue)
        assertEquals(62f / 56f, restingVisual.ratio!!)
    }

    @Test
    fun `rhr cards stay unavailable without a real baseline on calibrating day`() {
        val cards =
            factory.build(
                summary().copy(
                    restingHeartRate = 62,
                    restingHrRatio = 1.03f,
                    isCalibrating = true,
                    rhrBpm = null,
                ),
                preferences().copy(rhrBaselineOverride = null),
                date,
                null,
                null,
                null,
            )

        val sleepVisual = cards.getValue(CardId.SLEEP_RHR).visual as UniversalMetricVisual.PersonalBaseline
        val restingVisual = cards.getValue(CardId.RESTING_HR).visual as UniversalMetricVisual.PersonalBaseline

        assertEquals(UniversalMetricUnavailableReason.BASELINE_NOT_READY, sleepVisual.unavailableReason)
        assertEquals(UniversalMetricUnavailableReason.BASELINE_NOT_READY, restingVisual.unavailableReason)
        assertNull(sleepVisual.baselineValue)
        assertNull(restingVisual.baselineValue)
        assertNull(sleepVisual.ratio)
        assertNull(restingVisual.ratio)
    }

    @Test
    fun `hrv and rhr visuals stay ready on calibrating day when explicit overrides supply baselines`() {
        val cards =
            factory.build(
                summary().copy(
                    nocturnalHrv = 42,
                    restingHeartRate = 62,
                    isCalibrating = true,
                    rhrBpm = null,
                    hrvMuMssd = null,
                    hrvBaseline = null,
                ),
                preferences().copy(
                    hrvBaselineOverride = 40f,
                    hrvOptimalThreshold = 1.05f,
                    hrvWarningThreshold = 0.90f,
                    rhrBaselineOverride = 60f,
                    rhrOptimalThreshold = 1.05f,
                    rhrWarningThreshold = 1.15f,
                ),
                date,
                null,
                null,
                null,
            )

        val hrvVisual = cards.getValue(CardId.HRV).visual as UniversalMetricVisual.PersonalBaseline
        val sleepRhrVisual = cards.getValue(CardId.SLEEP_RHR).visual as UniversalMetricVisual.PersonalBaseline
        val restingHrVisual = cards.getValue(CardId.RESTING_HR).visual as UniversalMetricVisual.PersonalBaseline

        assertEquals(MetricStatus.OPTIMAL, cards.getValue(CardId.HRV).status)
        assertEquals(MetricStatus.OPTIMAL, cards.getValue(CardId.SLEEP_RHR).status)
        assertEquals(MetricStatus.OPTIMAL, cards.getValue(CardId.RESTING_HR).status)
        assertNull(hrvVisual.unavailableReason)
        assertNull(sleepRhrVisual.unavailableReason)
        assertNull(restingHrVisual.unavailableReason)
        assertEquals(42f / 40f, hrvVisual.ratio!!)
        assertEquals(62f / 60f, sleepRhrVisual.ratio!!)
        assertEquals(62f / 60f, restingHrVisual.ratio!!)
    }

    @Test
    fun `strain ratio uses bands from 0 to 2`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.STRAIN_RATIO).visual as UniversalMetricVisual.Score
        assertEquals(0f, visual.minValue)
        assertEquals(2f, visual.maxValue)
    }

    @Test
    fun `strain ratio uses lower-inclusive raw boundaries`() {
        val expectations =
            listOf(
                0.5f to MetricStatus.WARNING,
                0.8f to MetricStatus.OPTIMAL,
                1.3f to MetricStatus.WARNING,
                1.5f to MetricStatus.POOR,
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
        assertTrue(cards.getValue(CardId.HEART_RATE).visual is UniversalMetricVisual.ValueOnly)
        assertTrue(cards.getValue(CardId.BLOOD_PRESSURE).visual is UniversalMetricVisual.ValueOnly)
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
        assertEquals(MetricStatus.NEUTRAL, presentation.status)
    }

    @Test
    fun `missing heart rate summary is calibrating`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)

        assertEquals(MetricStatus.CALIBRATING, cards.getValue(CardId.HEART_RATE).status)
    }

    @Test
    fun `missing summary reports em dash and missing value for score based cards`() {
        val cards = factory.build(null, preferences(), date, null, null, null)

        val sleep = cards.getValue(CardId.SLEEP_SCORE)
        val sleepVisual = sleep.visual as UniversalMetricVisual.Score
        assertEquals("—", sleep.valueText)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, sleepVisual.unavailableReason)
        assertNull(sleepVisual.markerFraction)

        val readiness = cards.getValue(CardId.READINESS)
        val readinessVisual = readiness.visual as UniversalMetricVisual.Score
        assertEquals("—", readiness.valueText)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, readinessVisual.unavailableReason)
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
        val zeroHeightVisual = zeroHeightCards.getValue(CardId.WEIGHT).visual as UniversalMetricVisual.ReferenceRange
        assertFalse(zeroHeightVisual.selectionAvailable)
        assertEquals(UniversalMetricUnavailableReason.MISSING_BMI, zeroHeightVisual.unavailableReason)
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
        val nullHeightVisual = nullHeightCards.getValue(CardId.WEIGHT).visual as UniversalMetricVisual.ReferenceRange
        assertFalse(nullHeightVisual.selectionAvailable)
        assertEquals(UniversalMetricUnavailableReason.MISSING_BMI, nullHeightVisual.unavailableReason)
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
        val visual = cards.getValue(CardId.SLEEP_DURATION).visual as UniversalMetricVisual.Goal
        assertEquals(600f, visual.rawValue)
        assertEquals(1f, visual.markerFraction)
        assertTrue(visual.isAboveTarget)
    }

    @Test
    fun `missing sleep session reports missing value for sleep efficiency instead of a zero reading`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as UniversalMetricVisual.Score
        assertEquals("—", presentation.valueText)
        assertEquals(UniversalMetricUnavailableReason.MISSING_VALUE, visual.unavailableReason)
        assertNull(visual.markerFraction)
    }

    @Test
    fun `a genuine zero efficiency reading is treated as real data not missing`() {
        val lastSleepSession = SleepSessionSummary(efficiency = 0f, startTime = 0L, endTime = 0L)
        val cards = factory.build(summary(), preferences(), date, lastSleepSession, null, null)
        val presentation = cards.getValue(CardId.SLEEP_EFFICIENCY)
        val visual = presentation.visual as UniversalMetricVisual.Score
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
    fun `available cards announce the status used for their tint`() {
        stubAccessibilityStatusText()
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

        cards.values
            .filter { it.visual.unavailableReasonOrNull() == null }
            .forEach { presentation ->
                assertTrue(
                    "Expected ${presentation.status} in ${presentation.title}",
                    presentation.accessibilityDescription.contains(statusText(presentation.status)),
                )
            }
    }

    @Test
    fun `above-target sleep duration retains relation and announces its status`() {
        stubAccessibilityStatusText()

        val presentation =
            factory
                .build(
                    summary().copy(sleepDurationMinutes = 600),
                    preferences().copy(goalSleepHours = 8f),
                    date,
                    null,
                    null,
                    null,
                ).getValue(CardId.SLEEP_DURATION)

        assertEquals(MetricStatus.OPTIMAL, presentation.status)
        assertEquals(
            "${presentation.title}: ${presentation.valueText}, above target, ${statusText(presentation.status)}",
            presentation.accessibilityDescription,
        )
    }
}
