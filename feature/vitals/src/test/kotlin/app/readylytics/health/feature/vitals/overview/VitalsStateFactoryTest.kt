package app.readylytics.health.feature.vitals.overview

import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.util.UnitConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class VitalsStateFactoryTest {
    @Test
    fun `vitals range uses the persisted scoring timezone`() {
        val selectedDate = LocalDate.of(2026, 6, 10)
        val scoringZone = ZoneId.of("Pacific/Kiritimati")

        val result = resolveVitalsRangeWindow(TimeRange.SEVEN_DAYS, selectedDate, scoringZone)

        assertEquals(
            selectedDate
                .minusDays(6)
                .atStartOfDay(scoringZone)
                .toInstant()
                .toEpochMilli(),
            result.fromMs,
        )
        assertEquals(selectedDate.minusDays(6), result.startDate)
        assertEquals(selectedDate.atStartOfDay(scoringZone).toInstant().toEpochMilli(), result.selectedMidnightMs)
    }

    @Test
    fun `series builder pads and sorts each metric independently`() {
        val start = LocalDate.of(2026, 6, 1)
        val summaries =
            listOf(
                summary(date = start.plusDays(2), hrv = 42, rhr = 51, spo2 = 96.6),
                summary(date = start, hrv = 40, rhr = null, spo2 = 94.4),
            )

        val result = buildVitalsChartSeries(summaries, start, rangeDays = 7, unitSystem = UnitSystem.METRIC)

        assertEquals(7, result.hrv.size)
        assertEquals(40f, result.hrv[0].value)
        assertEquals(42f, result.hrv[2].value)
        assertNull(result.rhr[0].value)
        assertEquals(51f, result.rhr[2].value)
        assertEquals(94.4f, result.spo2[0].value)
        assertEquals(96.6f, result.spo2[2].value)
    }

    @Test
    fun `Vitals presentation uses rounded selected day HRV baseline for status and bands`() {
        val result =
            buildVitalsPresentationState(
                metrics = metrics(hrvBaselineRounded = 41),
                summary = summary(date = LocalDate.of(2026, 8, 8), hrv = 42),
                prefs = prefs(hrvOptimal = 1.10f),
            )

        assertEquals(MetricStatus.NEUTRAL, result.hrv.status)
        assertEquals(
            45.1,
            result.hrv.zoneBands!!
                .last()
                .lowerBound,
            0.001,
        )
    }

    @Test
    fun `Vitals presentation excludes default RHR fallback from personal assessment`() {
        val result =
            buildVitalsPresentationState(
                metrics =
                    metrics(
                        rhr = 63,
                        rhrBaselineRounded = null,
                        rhrSnapshotRaw = null,
                    ),
                summary = summary(date = LocalDate.of(2026, 8, 8), rhr = 63),
                prefs = prefs(rhrOverride = null),
            )

        assertEquals(MetricStatus.CALIBRATING, result.rhr.status)
        assertNull(result.rhr.baseline)
        assertNull(result.rhr.zoneBands)
    }

    @Test
    fun `Vitals presentation uses canonical RHR projection instead of raw snapshot`() {
        val result =
            buildVitalsPresentationState(
                metrics =
                    metrics(
                        rhr = 62,
                        rhrBaselineRounded = 56,
                        rhrSnapshotRaw = 60f,
                    ),
                summary = summary(date = LocalDate.of(2026, 8, 8), rhr = 62),
                prefs = prefs(rhrWarning = 1.10f, rhrOverride = null),
            )

        assertEquals(56, result.rhr.baseline)
        assertEquals(6, result.rhr.delta)
        assertEquals(MetricStatus.WARNING, result.rhr.status)
    }

    @Test
    fun `Vitals presentation treats explicit RHR override as personal baseline`() {
        val result =
            buildVitalsPresentationState(
                metrics =
                    metrics(
                        rhr = 63,
                        rhrBaselineRounded = 60,
                        rhrSnapshotRaw = null,
                    ),
                summary = summary(date = LocalDate.of(2026, 8, 8), rhr = 63),
                prefs = prefs(rhrOverride = 60f),
            )

        assertEquals(60, result.rhr.baseline)
        assertEquals(3, result.rhr.delta)
        assertNotNull(result.rhr.zoneBands)
    }

    @Test
    fun `Vitals chart retains raw SpO2 for zone placement`() {
        val start = LocalDate.of(2026, 8, 8)
        val series =
            buildVitalsChartSeries(
                summaries = listOf(DailySummary(date = start, avgSleepingSpo2 = 97.6f)),
                startDate = start,
                rangeDays = 1,
                unitSystem = UnitSystem.METRIC,
            )

        assertEquals(97.6f, series.spo2.single().value)
    }

    @Test
    fun `buildVitalsChartSeries includes a body temperature point per day, converted to the display unit`() {
        val startDate = LocalDate.of(2026, 6, 1)
        val summaries =
            listOf(
                DailySummary(date = startDate, avgSleepingBodyTemp = 36.5f),
                DailySummary(date = startDate.plusDays(1), avgSleepingBodyTemp = null),
                DailySummary(date = startDate.plusDays(2), avgSleepingBodyTemp = 37.1f),
            )

        val metricSeries = buildVitalsChartSeries(summaries, startDate, rangeDays = 3, unitSystem = UnitSystem.METRIC)
        assertEquals(36.5f, metricSeries.bodyTemp[0].value)
        assertEquals(null, metricSeries.bodyTemp[1].value)
        assertEquals(37.1f, metricSeries.bodyTemp[2].value)

        val imperialSeries =
            buildVitalsChartSeries(summaries, startDate, rangeDays = 3, unitSystem = UnitSystem.IMPERIAL)
        assertEquals(
            UnitConverter.celsiusToDisplayTemperature(36.5f, UnitSystem.IMPERIAL),
            imperialSeries.bodyTemp[0].value,
        )
    }

    @Test
    fun `buildVitalsPresentationState converts the body temperature baseline to the display unit`() {
        val metricState =
            buildVitalsPresentationState(
                metrics = metrics(hrvBaselineRounded = 50, rhrSnapshotRaw = 55f),
                summary = summary(date = LocalDate.of(2026, 6, 1)),
                prefs = prefs(unitSystem = UnitSystem.METRIC),
                bodyTemperatureBaselineCelsius = 36.7f,
            )
        assertEquals(36.7f, metricState.baselineBodyTemp)

        val imperialState =
            buildVitalsPresentationState(
                metrics = metrics(hrvBaselineRounded = 50, rhrSnapshotRaw = 55f),
                summary = summary(date = LocalDate.of(2026, 6, 1)),
                prefs = prefs(unitSystem = UnitSystem.IMPERIAL),
                bodyTemperatureBaselineCelsius = 36.7f,
            )
        assertEquals(
            UnitConverter.celsiusToDisplayTemperature(36.7f, UnitSystem.IMPERIAL),
            imperialState.baselineBodyTemp,
        )
    }

    private fun summary(
        date: LocalDate,
        hrv: Int? = null,
        rhr: Int? = null,
        spo2: Double? = null,
    ): DailySummary =
        DailySummary(
            date = date,
            nocturnalHrv = hrv,
            restingHeartRate = rhr,
            avgSleepingSpo2 = spo2?.toFloat(),
            isCalibrating = false,
        )

    private fun metrics(
        date: LocalDate = LocalDate.of(2026, 8, 8),
        hrv: Int? = null,
        rhr: Int? = null,
        hrvBaselineRounded: Int? = null,
        rhrBaselineRounded: Int? = null,
        rhrSnapshotRaw: Float? = null,
    ): DailyMetrics =
        DailyMetrics(
            date = date,
            nocturnalHrvRounded = hrv,
            nocturnalRhrRounded = rhr,
            hrvBaselineRounded = hrvBaselineRounded,
            rhrBaselineRounded = rhrBaselineRounded,
            rhrSnapshotRaw = rhrSnapshotRaw,
        )

    private fun prefs(
        hrvOptimal: Float = 0.9f,
        hrvWarning: Float = 0.8f,
        rhrOptimal: Float = 1.05f,
        rhrWarning: Float = 1.15f,
        rhrOverride: Float? = null,
        unitSystem: UnitSystem = UnitSystem.METRIC,
    ): UserPreferences =
        UserPreferences(
            hrvOptimalThreshold = hrvOptimal,
            hrvWarningThreshold = hrvWarning,
            rhrOptimalThreshold = rhrOptimal,
            rhrWarningThreshold = rhrWarning,
            rhrBaselineOverride = rhrOverride,
            unitSystem = unitSystem,
        )
}
