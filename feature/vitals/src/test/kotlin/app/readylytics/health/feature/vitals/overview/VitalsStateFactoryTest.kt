package app.readylytics.health.feature.vitals.overview

import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.model.Baselines
import app.readylytics.health.domain.model.DailySummary
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
        assertEquals(94f, result.spo2[0].value)
        assertEquals(97f, result.spo2[2].value)
    }

    @Test
    fun `zone state retains baselines and thresholds`() {
        val result =
            buildVitalsPresentationState(
                baselines = Baselines(hrv = 50f, rhr = 48),
                hrvOptimalThreshold = 0.9f,
                hrvWarningThreshold = 0.8f,
                rhrOptimalThreshold = 1.05f,
                rhrWarningThreshold = 1.15f,
                unitSystem = UnitSystem.METRIC,
            )

        assertEquals(50f, result.baselineHrv)
        assertEquals(48, result.baselineRhr)
        assertEquals(0.9f, result.hrvOptimalThreshold)
        assertEquals(1.15f, result.rhrWarningThreshold)
        assertNotNull(result.hrvZoneBands)
        assertNotNull(result.rhrZoneBands)
        assertNotNull(result.spo2ZoneBands)
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
                baselines = Baselines(hrv = 50f, rhr = 55, bodyTemp = 36.7f),
                hrvOptimalThreshold = 1.1f,
                hrvWarningThreshold = 0.9f,
                rhrOptimalThreshold = 0.9f,
                rhrWarningThreshold = 1.1f,
                unitSystem = UnitSystem.METRIC,
            )
        assertEquals(36.7f, metricState.baselineBodyTemp)

        val imperialState =
            buildVitalsPresentationState(
                baselines = Baselines(hrv = 50f, rhr = 55, bodyTemp = 36.7f),
                hrvOptimalThreshold = 1.1f,
                hrvWarningThreshold = 0.9f,
                rhrOptimalThreshold = 0.9f,
                rhrWarningThreshold = 1.1f,
                unitSystem = UnitSystem.IMPERIAL,
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
}
