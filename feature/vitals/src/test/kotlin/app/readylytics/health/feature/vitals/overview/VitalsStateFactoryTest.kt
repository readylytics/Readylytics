package app.readylytics.health.feature.vitals.overview

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.util.UnitConverter
import app.readylytics.health.core.ui.common.DailyDataPoint
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.common.TrendGranularity
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        val result =
            buildVitalsChartSeries(summaries, start, range = TimeRange.SEVEN_DAYS, unitSystem = UnitSystem.METRIC)

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
                range = TimeRange.SEVEN_DAYS,
                unitSystem = UnitSystem.METRIC,
            )

        assertEquals(97.6f, series.spo2[0].value)
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

        val metricSeries =
            buildVitalsChartSeries(summaries, startDate, range = TimeRange.SEVEN_DAYS, unitSystem = UnitSystem.METRIC)
        assertEquals(36.5f, metricSeries.bodyTemp[0].value)
        assertEquals(null, metricSeries.bodyTemp[1].value)
        assertEquals(37.1f, metricSeries.bodyTemp[2].value)

        val imperialSeries =
            buildVitalsChartSeries(summaries, startDate, range = TimeRange.SEVEN_DAYS, unitSystem = UnitSystem.IMPERIAL)
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
        assertEquals(36.7f, metricState.bodyTemp.baseline)

        val imperialState =
            buildVitalsPresentationState(
                metrics = metrics(hrvBaselineRounded = 50, rhrSnapshotRaw = 55f),
                summary = summary(date = LocalDate.of(2026, 6, 1)),
                prefs = prefs(unitSystem = UnitSystem.IMPERIAL),
                bodyTemperatureBaselineCelsius = 36.7f,
            )
        assertEquals(
            UnitConverter.celsiusToDisplayTemperature(36.7f, UnitSystem.IMPERIAL),
            imperialState.bodyTemp.baseline,
        )
        // A body temperature value absent from the summary yields a calibrating status.
        assertEquals(MetricStatus.CALIBRATING, metricState.bodyTemp.status)
        assertEquals(MetricStatus.CALIBRATING, imperialState.bodyTemp.status)
    }

    @Test
    fun `body temperature assessment status derives from deviation against baseline`() {
        val neutralState =
            buildVitalsPresentationState(
                metrics = metrics(hrvBaselineRounded = 50, rhrSnapshotRaw = 55f),
                summary = summary(date = LocalDate.of(2026, 6, 1), bodyTemp = 36.5f),
                prefs = prefs(),
                bodyTemperatureBaselineCelsius = 36.5f,
            )
        assertEquals(MetricStatus.NEUTRAL, neutralState.bodyTemp.status)
        assertEquals(36.5f, neutralState.bodyTemp.value)

        val warningState =
            buildVitalsPresentationState(
                metrics = metrics(hrvBaselineRounded = 50, rhrSnapshotRaw = 55f),
                summary = summary(date = LocalDate.of(2026, 6, 1), bodyTemp = 37.6f),
                prefs = prefs(),
                bodyTemperatureBaselineCelsius = 36.5f,
            )
        // 37.6 - 36.5 = 1.1 >= default threshold (1.0C) -> WARNING
        assertEquals(MetricStatus.WARNING, warningState.bodyTemp.status)

        val noBaselineState =
            buildVitalsPresentationState(
                metrics = metrics(hrvBaselineRounded = 50, rhrSnapshotRaw = 55f),
                summary = summary(date = LocalDate.of(2026, 6, 1), bodyTemp = 36.5f),
                prefs = prefs(),
                bodyTemperatureBaselineCelsius = null,
            )
        assertEquals(MetricStatus.NEUTRAL, noBaselineState.bodyTemp.status)
    }

    @Test
    fun `six months buckets hrv series into monthly averages with summary`() {
        val start = LocalDate.of(2026, 1, 1)
        val summaries =
            listOf(
                summary(date = start, hrv = 20),
                summary(date = start.plusDays(31), hrv = 24),
                summary(date = start.plusDays(59), hrv = 28),
                summary(date = start.plusDays(90), hrv = 32),
                summary(date = start.plusDays(120), hrv = 36),
                summary(date = start.plusDays(151), hrv = 40),
            )

        val series =
            buildVitalsChartSeries(summaries, start, range = TimeRange.SIX_MONTHS, unitSystem = UnitSystem.METRIC)

        assertEquals(listOf(15, 44, 74, 104, 135, 165), series.hrv.map { it.dayOffset })
        assertEquals(listOf(20f, 24f, 28f, 32f, 36f, 40f), series.hrv.map { it.value })
        assertEquals(40f, series.hrvPeriodSummary?.average)
        assertEquals(36f, series.hrvPeriodSummary?.previousAverage)
    }

    @Test
    fun `twelve months buckets series into eight week averages`() {
        val start = LocalDate.of(2026, 1, 1)
        val summaries =
            listOf(
                summary(date = start, hrv = 10),
                summary(date = start.plusDays(90), hrv = 20),
                summary(date = start.plusDays(181), hrv = 30),
                summary(date = start.plusDays(273), hrv = 40),
            )

        val series =
            buildVitalsChartSeries(summaries, start, range = TimeRange.TWELVE_MONTHS, unitSystem = UnitSystem.METRIC)

        assertEquals(listOf(24, 80, 192, 248), series.hrv.map { it.dayOffset })
        assertEquals(listOf(10f, 20f, 30f, 40f), series.hrv.map { it.value })
        assertEquals(TrendGranularity.EIGHT_WEEK, series.hrvPeriodSummary?.granularity)
        assertEquals(start.plusDays(248), series.hrvPeriodSummary?.periodStartDate)
        assertEquals(40f, series.hrvPeriodSummary?.average)
        assertEquals(30f, series.hrvPeriodSummary?.previousAverage)
    }

    @Test
    fun `monthly buckets skip months without data`() {
        val start = LocalDate.of(2026, 1, 1)
        val summaries =
            listOf(
                summary(date = start, hrv = 10),
                summary(date = start.plusDays(59), hrv = 50),
            )

        val series =
            buildVitalsChartSeries(summaries, start, range = TimeRange.SIX_MONTHS, unitSystem = UnitSystem.METRIC)

        assertEquals(listOf(15, 74), series.hrv.map { it.dayOffset })
    }

    @Test
    fun `single populated bucket yields no period summary`() {
        val start = LocalDate.of(2026, 1, 1)
        val summaries = listOf(summary(date = start, hrv = 20))

        val series =
            buildVitalsChartSeries(summaries, start, range = TimeRange.SIX_MONTHS, unitSystem = UnitSystem.METRIC)

        assertEquals(listOf(DailyDataPoint(15, 20f)), series.hrv)
        assertNull(series.hrvPeriodSummary)
    }

    @Test
    fun `all four metrics bucket together with independent averages`() {
        val start = LocalDate.of(2026, 1, 1)
        val summaries =
            listOf(
                DailySummary(
                    date = start,
                    nocturnalHrv = 20,
                    restingHeartRate = 51,
                    avgSleepingSpo2 = 94.4f,
                    avgSleepingBodyTemp = 36.5f,
                    isCalibrating = false,
                ),
                DailySummary(
                    date = start.plusDays(31),
                    nocturnalHrv = 24,
                    restingHeartRate = 53,
                    avgSleepingSpo2 = 96.6f,
                    avgSleepingBodyTemp = 37.1f,
                    isCalibrating = false,
                ),
            )

        val series =
            buildVitalsChartSeries(summaries, start, range = TimeRange.SIX_MONTHS, unitSystem = UnitSystem.METRIC)

        assertEquals(listOf(15, 44), series.hrv.map { it.dayOffset })
        assertEquals(listOf(15, 44), series.rhr.map { it.dayOffset })
        assertEquals(listOf(15, 44), series.spo2.map { it.dayOffset })
        assertEquals(listOf(15, 44), series.bodyTemp.map { it.dayOffset })

        assertEquals(listOf(20f, 24f), series.hrv.map { it.value })
        assertEquals(listOf(51f, 53f), series.rhr.map { it.value })
        assertEquals(listOf(94f, 97f), series.spo2.map { it.value })
        assertEquals(listOf(36.5f, 37.1f), series.bodyTemp.map { it.value })

        assertEquals(24f, series.hrvPeriodSummary?.average)
        assertEquals(53f, series.rhrPeriodSummary?.average)
        assertEquals(97f, series.spo2PeriodSummary?.average)
        assertEquals(37.1f, series.bodyTempPeriodSummary?.average)
    }

    @Test
    fun `DAILY range produces empty historical baseline series`() {
        val summaries =
            listOf(
                dailySummary(date = LocalDate.of(2026, 1, 1), rhrBpm = 60f, hrvMuMssd = 3.8f),
            )
        val result =
            buildVitalsChartSeries(summaries, LocalDate.of(2026, 1, 1), TimeRange.SEVEN_DAYS, UnitSystem.METRIC)

        assertTrue(result.historicalRhrBaseline.isEmpty())
        assertTrue(result.historicalHrvBaseline.isEmpty())
        assertTrue(result.historicalRhrZoneBands.isEmpty())
        assertTrue(result.historicalHrvZoneBands.isEmpty())
        assertTrue(result.historicalRhrBucketZoneBands.isEmpty())
        assertTrue(result.historicalHrvBucketZoneBands.isEmpty())
        assertNull(result.historicalRhrBaselineAverage)
        assertNull(result.historicalHrvBaselineAverage)
    }

    @Test
    fun `historical baseline averages only frozen days per bucket`() {
        val frozen =
            dailySummary(
                date = LocalDate.of(2026, 1, 1),
                rhrBpm = 62f,
                hrvMuMssd = 3.9f,
                baselineCalculatedAt = LocalDate.of(2026, 1, 1),
            )
        val frozen2 =
            dailySummary(
                date = LocalDate.of(2026, 1, 15),
                rhrBpm = 58f,
                hrvMuMssd = 4.0f,
                baselineCalculatedAt = LocalDate.of(2026, 1, 15),
            )
        val unfrozen =
            dailySummary(
                date = LocalDate.of(2026, 1, 20),
                rhrBpm = 70f,
                hrvMuMssd = 3.5f,
                baselineCalculatedAt = null,
            )
        val summaries = listOf(frozen, frozen2, unfrozen)
        val result =
            buildVitalsChartSeries(summaries, LocalDate.of(2026, 1, 1), TimeRange.SIX_MONTHS, UnitSystem.METRIC)

        val rhrBaseline = result.historicalRhrBaseline
        assertTrue("Expected populated RHR baseline, got empty", rhrBaseline.isNotEmpty())
        val avgValue = rhrBaseline.first().value!!
        assertTrue("Expected frozen-day average ~60, got $avgValue", avgValue in 59f..61f)
        assertEquals(60, result.historicalRhrBaselineAverage)
        assertTrue(
            "Expected per-bucket RHR zone bands, got empty",
            result.historicalRhrBucketZoneBands.isNotEmpty(),
        )
        assertEquals(1, result.historicalRhrBucketZoneBands.size)
        // Zone band must span the true calendar bucket (Jan 1-31 -> offsets 0..31), not the
        // bucket's midpoint day offset (rhrBaseline.first().dayOffset is the midpoint).
        assertEquals(0, result.historicalRhrBucketZoneBands.first().startDayOffset)
        assertEquals(31, result.historicalRhrBucketZoneBands.first().endDayOffset)
    }

    @Test
    fun `historical zone bands empty when no frozen baselines`() {
        val summaries =
            listOf(
                dailySummary(date = LocalDate.of(2026, 1, 1), rhrBpm = null, hrvMuMssd = null),
            )
        val result =
            buildVitalsChartSeries(summaries, LocalDate.of(2026, 1, 1), TimeRange.SIX_MONTHS, UnitSystem.METRIC)

        assertTrue(result.historicalRhrZoneBands.isEmpty())
        assertTrue(result.historicalHrvZoneBands.isEmpty())
        assertTrue(result.historicalRhrBucketZoneBands.isEmpty())
        assertTrue(result.historicalHrvBucketZoneBands.isEmpty())
        assertNull(result.historicalRhrBaselineAverage)
        assertNull(result.historicalHrvBaselineAverage)
    }

    @Test
    fun `historical baseline honors override when no frozen days`() {
        val summaries =
            listOf(
                dailySummary(date = LocalDate.of(2026, 1, 1), rhrBpm = null, hrvMuMssd = null),
            )
        val result =
            buildVitalsChartSeries(
                summaries,
                LocalDate.of(2026, 1, 1),
                TimeRange.SIX_MONTHS,
                UnitSystem.METRIC,
                rhrBaselineOverride = 62f,
                hrvBaselineOverride = 41f,
            )

        assertEquals(62, result.historicalRhrBaselineAverage)
        assertEquals(41, result.historicalHrvBaselineAverage)
        assertTrue(result.historicalRhrZoneBands.isNotEmpty())
        assertTrue(result.historicalHrvZoneBands.isNotEmpty())
        assertTrue(result.historicalRhrBucketZoneBands.isNotEmpty())
        assertTrue(result.historicalHrvBucketZoneBands.isNotEmpty())
    }

    private fun dailySummary(
        date: LocalDate,
        rhrBpm: Float? = null,
        hrvMuMssd: Float? = null,
        baselineCalculatedAt: LocalDate? = null,
    ): DailySummary =
        DailySummary(
            date = date,
            rhrBpm = rhrBpm,
            hrvMuMssd = hrvMuMssd,
            baselineCalculatedAtDate = baselineCalculatedAt,
            isCalibrating = false,
        )

    private fun summary(
        date: LocalDate,
        hrv: Int? = null,
        rhr: Int? = null,
        spo2: Double? = null,
        bodyTemp: Float? = null,
    ): DailySummary =
        DailySummary(
            date = date,
            nocturnalHrv = hrv,
            restingHeartRate = rhr,
            avgSleepingSpo2 = spo2?.toFloat(),
            avgSleepingBodyTemp = bodyTemp,
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
