package app.readylytics.health.feature.dashboard.usecase
import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.model.SleepSessionSummary
import app.readylytics.health.core.ui.components.GOAL_FILL_CAP_FRACTION
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricUnavailableReason
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import io.mockk.every
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import app.readylytics.health.core.ui.R as CoreUiR
import app.readylytics.health.feature.dashboard.R as DashboardR

class DashboardMetricPresentationVitalsTest : DashboardMetricPresentationFactoryTestBase() {
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
        val sleep = cards.getValue(CardId.SLEEP_SCORE).visual as UniversalMetricVisual.Score
        val readiness = cards.getValue(CardId.READINESS).visual as UniversalMetricVisual.Score
        assertEquals(0f, sleep.minValue)
        assertEquals(100f, sleep.maxValue)
        assertEquals(0f, readiness.minValue)
        assertEquals(100f, readiness.maxValue)
    }

    @Test
    fun `score boundaries classify the upper band`() {
        val expectations =
            listOf(
                40f to MetricStatus.WARNING,
                60f to MetricStatus.NEUTRAL,
                85f to MetricStatus.OPTIMAL,
                100f to MetricStatus.OPTIMAL,
            )

        expectations.forEach { (score, expected) ->
            val cards =
                factory.build(
                    summary().copy(sleepScore = score, readinessWorkoutOnly = score),
                    preferences(),
                    date,
                    null,
                    null,
                    null,
                )

            assertEquals(expected, cards.getValue(CardId.SLEEP_SCORE).status)
            assertEquals(expected, cards.getValue(CardId.READINESS).status)
        }
    }

    @Test
    fun `sleep efficiency and spo2 use raw lower-inclusive status ladders`() {
        listOf(
            68f to MetricStatus.POOR,
            78f to MetricStatus.WARNING,
        ).forEach { (efficiency, expected) ->
            val cards =
                factory.build(
                    summary(),
                    preferences(),
                    date,
                    SleepSessionSummary(efficiency = efficiency, startTime = 0L, endTime = 0L),
                    null,
                    null,
                )

            assertEquals(expected, cards.getValue(CardId.SLEEP_EFFICIENCY).status)
        }

        listOf(
            89f to MetricStatus.POOR,
            90f to MetricStatus.WARNING,
            95f to MetricStatus.NEUTRAL,
            98f to MetricStatus.OPTIMAL,
        ).forEach { (spo2, expected) ->
            val cards =
                factory.build(
                    summary().copy(avgSleepingSpo2 = spo2),
                    preferences(),
                    date,
                    null,
                    null,
                    null,
                )

            assertEquals(expected, cards.getValue(CardId.OXYGEN_SATURATION).status)
        }
    }

    @Test
    fun `dashboard hrv uses displayed baseline for 110 percent boundary and tooltip`() {
        every { resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv) } returns "HRV"
        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_hrv_baseline, 41, "↑", 1)
        } returns "\n\nBaseline: 41 ms ↑ (1 ms)"

        val cards =
            factory.build(
                summary().copy(
                    nocturnalHrv = 42,
                    hrvBaseline = 38,
                    hrvMuMssd = ln(41.0).toFloat(),
                ),
                preferences(),
                date,
                null,
                null,
                null,
            )

        val hrv = cards.getValue(CardId.HRV)
        assertEquals(MetricStatus.NEUTRAL, hrv.status)
        assertTrue(hrv.tooltip.contains("Baseline: 41"))
    }

    @Test
    fun `dashboard rhr uses override rounded baseline for both cards status and delta`() {
        every { resourceProvider.getString(CoreUiR.string.delta_up) } returns "↑"
        every { resourceProvider.getString(CoreUiR.string.delta_up_format, *anyVararg()) } returns "↑ 6 bpm"

        val cards =
            factory.build(
                summary().copy(
                    restingHeartRate = 62,
                    restingHrRatio = 1.11f,
                    rhrBpm = 60f,
                    baselineCalculatedAtDate = null,
                ),
                preferences().copy(rhrBaselineOverride = 55.6f),
                date,
                null,
                null,
                null,
            )

        val sleepRhr = cards.getValue(CardId.SLEEP_RHR)
        val restingHr = cards.getValue(CardId.RESTING_HR)

        assertEquals(MetricStatus.WARNING, sleepRhr.status)
        assertEquals("↑ 6 bpm", sleepRhr.secondaryText)
        assertEquals(MetricStatus.WARNING, restingHr.status)
        assertEquals("↑ 6 bpm", restingHr.secondaryText)
        assertEquals(56f, (sleepRhr.visual as UniversalMetricVisual.PersonalBaseline).baselineValue)
        assertEquals(56f, (restingHr.visual as UniversalMetricVisual.PersonalBaseline).baselineValue)
    }

    @Test
    fun `dashboard rhr stays calibrating with no baseline on a calibrating day`() {
        every { resourceProvider.getString(CoreUiR.string.tooltip_sleep_rhr) } returns
            "Average heart rate during sleep."
        every {
            resourceProvider.getString(CoreUiR.string.tooltip_sleep_rhr_no_baseline)
        } returns "\n\nNot enough data to calculate baseline."
        every {
            resourceProvider.getString(DashboardR.string.tooltip_resting_hr_no_baseline)
        } returns "Resting heart rate calculated as the low-percentile baseline."

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

        val sleepRhr = cards.getValue(CardId.SLEEP_RHR)
        val restingHr = cards.getValue(CardId.RESTING_HR)

        assertEquals(MetricStatus.CALIBRATING, sleepRhr.status)
        assertEquals(MetricStatus.CALIBRATING, restingHr.status)
        assertNull(sleepRhr.secondaryText)
        assertNull(restingHr.secondaryText)
        assertEquals("Average heart rate during sleep.\n\nNot enough data to calculate baseline.", sleepRhr.tooltip)
        assertEquals("Resting heart rate calculated as the low-percentile baseline.", restingHr.tooltip)
    }

    @Test
    fun `dashboard rhr ignores calibrating synthetic default without baseline marker`() {
        val cards =
            factory.build(
                summary().copy(
                    restingHeartRate = 62,
                    restingHrRatio = 1.03f,
                    isCalibrating = true,
                    rhrBpm = 60f,
                    baselineCalculatedAtDate = null,
                ),
                preferences().copy(rhrBaselineOverride = null),
                date,
                null,
                null,
                null,
            )

        val sleepRhr = cards.getValue(CardId.SLEEP_RHR)
        val restingHr = cards.getValue(CardId.RESTING_HR)
        val sleepVisual = sleepRhr.visual as UniversalMetricVisual.PersonalBaseline
        val restingVisual = restingHr.visual as UniversalMetricVisual.PersonalBaseline

        assertEquals(MetricStatus.CALIBRATING, sleepRhr.status)
        assertEquals(MetricStatus.CALIBRATING, restingHr.status)
        assertNull(sleepRhr.secondaryText)
        assertNull(restingHr.secondaryText)
        assertEquals(UniversalMetricUnavailableReason.BASELINE_NOT_READY, sleepVisual.unavailableReason)
        assertEquals(UniversalMetricUnavailableReason.BASELINE_NOT_READY, restingVisual.unavailableReason)
        assertNull(sleepVisual.baselineValue)
        assertNull(restingVisual.baselineValue)
    }

    @Test
    fun `blood pressure card uses canonical inclusive component-wise status ladder`() {
        val expected =
            listOf(
                (120 to 80) to MetricStatus.OPTIMAL,
                (121 to 80) to MetricStatus.NEUTRAL,
                (120 to 81) to MetricStatus.NEUTRAL,
                (129 to 89) to MetricStatus.NEUTRAL,
                (130 to 90) to MetricStatus.WARNING,
            )

        expected.forEach { (reading, status) ->
            val card =
                factory
                    .build(
                        summary().copy(
                            bloodPressureSystolic = reading.first,
                            bloodPressureDiastolic = reading.second,
                        ),
                        preferences(),
                        date,
                        null,
                        null,
                        null,
                    ).getValue(CardId.BLOOD_PRESSURE)

            assertEquals("Blood pressure ${reading.first}/${reading.second}", status, card.status)
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
        val visual = readiness.visual as UniversalMetricVisual.Score
        assertEquals(84.6f, visual.rawValue)
        assertEquals("85", readiness.valueText)
        assertEquals(MetricStatus.NEUTRAL, readiness.status)
    }

    @Test
    fun `weight keeps real value and positions its reference midpoint`() {
        val cards =
            factory.build(
                summary(weightKg = 66.45625f),
                preferences(heightCm = 175f),
                date,
                null,
                null,
                null,
            )
        val card = cards.getValue(CardId.WEIGHT)
        val visual = card.visual as UniversalMetricVisual.ReferenceRange
        assertEquals(0.5f, visual.referenceMarkerFraction)
        assertEquals(0.5f, visual.markerFraction)
    }

    @Test
    fun `weight card status matches canonical bmi assessment boundaries`() {
        val expected =
            listOf(
                18.4f to MetricStatus.WARNING,
                18.5f to MetricStatus.OPTIMAL,
                24.9f to MetricStatus.OPTIMAL,
                25f to MetricStatus.WARNING,
                29.9f to MetricStatus.WARNING,
                30f to MetricStatus.POOR,
            )

        expected.forEach { (bmi, status) ->
            val card =
                factory
                    .build(
                        summary(weightKg = bmi),
                        preferences(heightCm = 100f),
                        date,
                        null,
                        null,
                        null,
                    ).getValue(CardId.WEIGHT)

            assertEquals("BMI $bmi", status, card.status)
        }
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
        val visual = cards.getValue(CardId.BODY_FAT).visual as UniversalMetricVisual.ReferenceRange
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
        val visual = cards.getValue(CardId.SLEEP_DURATION).visual as UniversalMetricVisual.Goal
        assertEquals(480f, visual.targetValue)
        assertEquals(450f, visual.rawValue)
    }

    @Test
    fun `ras permits overflow beyond 100`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.RAS_DAILY).visual as UniversalMetricVisual.Score
        assertEquals(100f / GOAL_FILL_CAP_FRACTION, visual.maxValue)
    }

    @Test
    fun `circadian score bounds are 0 to 100`() {
        val circResult =
            app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
                .Ready(85f, 0, 0, 0, 0)
        val cards = factory.build(summary(), preferences(), date, null, circResult, null)
        val visual = cards.getValue(CardId.CIRCADIAN_CONSISTENCY).visual as UniversalMetricVisual.Score
        assertEquals(0f, visual.minValue)
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `circadian consistency displays its rounded score as a percentage`() {
        val circResult =
            app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
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
        val visual = presentation.visual as UniversalMetricVisual.Score

        assertEquals("95%", presentation.valueText)
        assertEquals(MetricStatus.OPTIMAL, presentation.status)
        assertEquals(95.4f, visual.rawValue)
        assertEquals("Circadian: 95 of 100, mock_string", presentation.accessibilityDescription)
    }

    @Test
    fun `sleep efficiency uses 0 to 100 bounds`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.SLEEP_EFFICIENCY).visual as UniversalMetricVisual.Score
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
        val visual = presentation.visual as UniversalMetricVisual.Score

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
        val visual = presentation.visual as UniversalMetricVisual.Score

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
    fun `dashboard spo2 keeps raw 97 point 6 neutral while displaying rounded 98 percent`() {
        val cards =
            factory.build(
                summary().copy(avgSleepingSpo2 = 97.6f),
                preferences(),
                date,
                null,
                null,
                null,
            )

        val presentation = cards.getValue(CardId.OXYGEN_SATURATION)
        assertEquals(MetricStatus.NEUTRAL, presentation.status)
        assertEquals("98%", presentation.valueText)
    }

    @Test
    fun `body temperature card shows Calibrating secondary text when baseline is not yet available`() {
        every { resourceProvider.getString(CoreUiR.string.body_temperature_calibrating) } returns "Calibrating"
        val summary = summary().copy(avgSleepingBodyTemp = 37.0f)

        val result =
            factory.build(
                summary = summary,
                preferences = preferences(),
                selectedDate = date,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
                bodyTempBaseline = null,
            )

        val presentation = result.getValue(CardId.BODY_TEMPERATURE)
        // Baseline missing -> NEUTRAL, not CALIBRATING (the reading itself is present; only the
        // baseline is still warming up).
        assertEquals(MetricStatus.NEUTRAL, presentation.status)
        assertTrue(presentation.secondaryText!!.contains("Calibrating"))
    }

    @Test
    fun `body temperature card status is WARNING when deviation meets the configured threshold`() {
        val summary = summary().copy(avgSleepingBodyTemp = 38.0f)
        val prefs = UserPreferences(bodyTempElevatedThresholdCelsius = 1.0f)

        val result =
            factory.build(
                summary = summary,
                preferences = prefs,
                selectedDate = date,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
                bodyTempBaseline = 36.9f,
            )

        assertEquals(MetricStatus.WARNING, result.getValue(CardId.BODY_TEMPERATURE).status)
    }

    @Test
    fun `body temperature card status is NEUTRAL when deviation is below the configured threshold`() {
        val summary = summary().copy(avgSleepingBodyTemp = 37.2f)
        val prefs = UserPreferences(bodyTempElevatedThresholdCelsius = 1.0f)

        val result =
            factory.build(
                summary = summary,
                preferences = prefs,
                selectedDate = date,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
                bodyTempBaseline = 36.9f,
            )

        assertEquals(MetricStatus.NEUTRAL, result.getValue(CardId.BODY_TEMPERATURE).status)
    }

    @Test
    fun `body temperature gauge bounds convert to the display unit like the headline value`() {
        val summary = summary().copy(avgSleepingBodyTemp = 37.0f)
        val prefs = UserPreferences(unitSystem = UnitSystem.IMPERIAL)

        val result =
            factory.build(
                summary = summary,
                preferences = prefs,
                selectedDate = date,
                lastSleepSession = null,
                circadianResult = null,
                heartRateSummary = null,
                bodyTempBaseline = null,
            )

        val visual = result.getValue(CardId.BODY_TEMPERATURE).visual as UniversalMetricVisual.Score
        // 37.0C -> 98.6F headline; scale bounds 35.5C/39C -> 95.9F/102.2F, matching the same unit.
        assertEquals(98.6f, visual.rawValue!!, 0.01f)
        assertEquals(95.9f, visual.minValue, 0.01f)
        assertEquals(102.2f, visual.maxValue, 0.01f)
    }
}
