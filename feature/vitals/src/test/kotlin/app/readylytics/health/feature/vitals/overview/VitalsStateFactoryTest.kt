package app.readylytics.health.feature.vitals.overview

import app.readylytics.health.core.ui.model.Baselines
import app.readylytics.health.domain.model.DailySummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class VitalsStateFactoryTest {
    @Test
    fun `series builder pads and sorts each metric independently`() {
        val start = LocalDate.of(2026, 6, 1)
        val summaries =
            listOf(
                summary(date = start.plusDays(2), hrv = 42, rhr = 51, spo2 = 96.6),
                summary(date = start, hrv = 40, rhr = null, spo2 = 94.4),
            )

        val result = buildVitalsChartSeries(summaries, start, rangeDays = 7)

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
            )

        assertEquals(50f, result.baselineHrv)
        assertEquals(48, result.baselineRhr)
        assertEquals(0.9f, result.hrvOptimalThreshold)
        assertEquals(1.15f, result.rhrWarningThreshold)
        assertNotNull(result.hrvZoneBands)
        assertNotNull(result.rhrZoneBands)
        assertNotNull(result.spo2ZoneBands)
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
