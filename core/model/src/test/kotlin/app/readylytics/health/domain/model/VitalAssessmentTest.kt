package app.readylytics.health.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class VitalAssessmentTest {
    @Test
    fun `HRV 42 with displayed baseline 41 and 110 percent optimum is neutral`() {
        val assessment = assessHrv(value = 42, baseline = 41, optimalRatio = 1.10f, warningRatio = 0.90f)

        assertEquals(MetricStatus.NEUTRAL, assessment.status)
        assertEquals(41, assessment.baseline)
        assertEquals(1, assessment.delta)
        assertEquals(42f / 41f, assessment.ratio ?: 0f, 0.0001f)
        assertEquals(45.1, assessment.zoneBands.orEmpty().single { it.zone == HealthZone.OPTIMAL }.lowerBound, 0.0001)
    }

    @Test
    fun `RHR thresholds use the displayed rounded baseline`() {
        assertEquals(
            MetricStatus.OPTIMAL,
            assessRhr(value = 50, baseline = 50, optimalRatio = 1.0f, warningRatio = 1.1f).status,
        )
        assertEquals(
            MetricStatus.NEUTRAL,
            assessRhr(value = 51, baseline = 50, optimalRatio = 1.0f, warningRatio = 1.1f).status,
        )
    }

    @Test
    fun `SpO2 97 point 6 remains neutral even when formatted text rounds to 98`() {
        assertEquals(MetricStatus.NEUTRAL, assessSpo2(97.6f).status)
    }

    @Test
    fun `personal baseline assessments calibrate without a positive displayed baseline`() {
        val missingBaseline = assessHrv(value = 42, baseline = null, optimalRatio = 1.10f, warningRatio = 0.90f)
        val zeroBaseline = assessRhr(value = 50, baseline = 0, optimalRatio = 1.0f, warningRatio = 1.1f)
        val missingValue = assessRhr(value = null, baseline = 50, optimalRatio = 1.0f, warningRatio = 1.1f)

        assertEquals(MetricStatus.CALIBRATING, missingBaseline.status)
        assertNull(missingBaseline.ratio)
        assertNull(missingBaseline.delta)
        assertNull(missingBaseline.zoneBands)

        assertEquals(MetricStatus.CALIBRATING, zeroBaseline.status)
        assertNull(zeroBaseline.ratio)
        assertNull(zeroBaseline.delta)
        assertNull(zeroBaseline.zoneBands)

        assertEquals(MetricStatus.CALIBRATING, missingValue.status)
        assertNull(missingValue.ratio)
        assertNull(missingValue.delta)
    }

    @Test
    fun `HRV boundaries are inclusive at optimal and warning thresholds`() {
        assertEquals(
            MetricStatus.OPTIMAL,
            assessHrv(value = 11, baseline = 10, optimalRatio = 1.10f, warningRatio = 0.90f).status,
        )
        assertEquals(
            MetricStatus.WARNING,
            assessHrv(value = 9, baseline = 10, optimalRatio = 1.10f, warningRatio = 0.90f).status,
        )
        assertEquals(
            MetricStatus.WARNING,
            assessHrv(value = 8, baseline = 10, optimalRatio = 1.10f, warningRatio = 0.90f).status,
        )
        assertEquals(
            MetricStatus.POOR,
            assessHrv(value = 7, baseline = 10, optimalRatio = 1.10f, warningRatio = 0.90f).status,
        )

        val bands = assessHrv(value = 10, baseline = 10, optimalRatio = 1.10f, warningRatio = 0.90f).zoneBands.orEmpty()
        assertEquals(HealthZone.OPTIMAL, bands.zoneAt(11.0))
        assertEquals(HealthZone.WARNING, bands.zoneAt(9.0))
        assertEquals(HealthZone.WARNING, bands.zoneAt(8.0))
        assertEquals(HealthZone.CRITICAL, bands.zoneAt(Math.nextDown(8.0)))
    }

    @Test
    fun `RHR boundaries are inclusive at optimal and warning thresholds`() {
        assertEquals(
            MetricStatus.OPTIMAL,
            assessRhr(value = 20, baseline = 20, optimalRatio = 1.0f, warningRatio = 1.1f).status,
        )
        assertEquals(
            MetricStatus.NEUTRAL,
            assessRhr(value = 21, baseline = 20, optimalRatio = 1.0f, warningRatio = 1.1f).status,
        )
        assertEquals(
            MetricStatus.WARNING,
            assessRhr(value = 22, baseline = 20, optimalRatio = 1.0f, warningRatio = 1.1f).status,
        )
        assertEquals(
            MetricStatus.POOR,
            assessRhr(value = 24, baseline = 20, optimalRatio = 1.0f, warningRatio = 1.1f).status,
        )

        val bands = assessRhr(value = 20, baseline = 20, optimalRatio = 1.0f, warningRatio = 1.1f).zoneBands.orEmpty()
        assertEquals(HealthZone.OPTIMAL, bands.zoneAt(20.0))
        assertEquals(HealthZone.NEUTRAL, bands.zoneAt(Math.nextUp(20.0)))
        assertEquals(HealthZone.WARNING, bands.zoneAt(22.0))
        assertEquals(HealthZone.CRITICAL, bands.zoneAt(24.0))
    }

    @Test
    fun `SpO2 thresholds use raw float values`() {
        val assessment = assessSpo2(null)

        assertEquals(MetricStatus.CALIBRATING, assessment.status)
        assertEquals(MetricStatus.OPTIMAL, assessSpo2(98.0f).status)
        assertEquals(MetricStatus.NEUTRAL, assessSpo2(95.0f).status)
        assertEquals(MetricStatus.WARNING, assessSpo2(90.0f).status)
        assertEquals(MetricStatus.POOR, assessSpo2(89.9f).status)
        assertEquals(HealthZone.WARNING, assessment.zoneBands.zoneAt(90.0))
        assertEquals(HealthZone.NEUTRAL, assessment.zoneBands.zoneAt(95.0))
        assertEquals(HealthZone.OPTIMAL, assessment.zoneBands.zoneAt(98.0))
    }

    @Test
    fun `DailySummary RHR wrappers use the rounded baseline seam instead of stored ratio drift`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 8, 8),
                restingHeartRate = 51,
                restingHrRatio = 51f / 50.4f,
                rhrBpm = 50.4f,
            )

        assertEquals(MetricStatus.WARNING, summary.rhrStatus(optimalThreshold = 1.0f, warningThreshold = 1.02f))
        assertEquals(MetricStatus.WARNING, summary.restingHrStatus(optimalThreshold = 1.0f, warningThreshold = 1.02f))
    }

    @Test
    fun `DailySummary RHR wrappers preserve calibrating semantics when stored ratio is missing`() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 8, 8),
                restingHeartRate = 51,
                rhrBpm = 50.4f,
            )

        assertEquals(MetricStatus.CALIBRATING, summary.rhrStatus(optimalThreshold = 1.0f, warningThreshold = 1.02f))
        assertEquals(MetricStatus.CALIBRATING, summary.restingHrStatus(optimalThreshold = 1.0f, warningThreshold = 1.02f))
    }

    @Test
    fun `rhrZoneBandsForBaseline matches assessRhr zone bands`() {
        val baseline = 60
        val optimalRatio = 1.1f
        val warningRatio = 1.3f
        val assessment = assessRhr(65, baseline, optimalRatio, warningRatio)
        val direct = rhrZoneBandsForBaseline(baseline, optimalRatio, warningRatio)
        assertEquals(assessment.zoneBands?.size, direct.size)
        assessment.zoneBands?.zip(direct)?.forEachIndexed { i, (a, b) ->
            assertEquals("Band $i lowerBound mismatch", a.lowerBound, b.lowerBound, 0.001)
            assertEquals("Band $i upperBound mismatch", a.upperBound, b.upperBound, 0.001)
            assertEquals("Band $i zone mismatch", a.zone, b.zone)
        }
    }

    @Test
    fun `hrvZoneBandsForBaseline matches assessHrv zone bands`() {
        val baseline = 41
        val optimalRatio = 1.1f
        val warningRatio = 0.6f
        val assessment = assessHrv(45, baseline, optimalRatio, warningRatio)
        val direct = hrvZoneBandsForBaseline(baseline, optimalRatio, warningRatio)
        assertEquals(assessment.zoneBands?.size, direct.size)
        assessment.zoneBands?.zip(direct)?.forEachIndexed { i, (a, b) ->
            assertEquals("Band $i lowerBound mismatch", a.lowerBound, b.lowerBound, 0.001)
            assertEquals("Band $i upperBound mismatch", a.upperBound, b.upperBound, 0.001)
            assertEquals("Band $i zone mismatch", a.zone, b.zone)
        }
    }

    @Test
    fun `rhrZoneBandsForBaseline computes four bands for zero baseline`() {
        val bands = rhrZoneBandsForBaseline(0, 1.1f, 1.3f)
        assertEquals(4, bands.size)
    }

    @Test
    fun `hrvZoneBandsForBaseline computes four bands for zero baseline`() {
        val bands = hrvZoneBandsForBaseline(0, 1.1f, 0.6f)
        assertEquals(4, bands.size)
    }

    private fun List<ZoneBand>.zoneAt(value: Double): HealthZone =
        single { band ->
            val aboveMinimum =
                if (band.includesMinimum) value >= band.lowerBound else value > band.lowerBound
            val belowMaximum =
                if (band.includesMaximum) value <= band.upperBound else value < band.upperBound
            aboveMinimum && belowMaximum
        }.zone
}
