package app.readylytics.health.domain.model

import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyCompositionZoneBandsTest {
    @Test
    fun `BMI chart bands use the same warning and poor boundaries as the canonical assessment`() {
        assertEquals(
            listOf(
                ZoneBand(Double.NEGATIVE_INFINITY, 18.5, HealthZone.WARNING),
                ZoneBand(18.5, 25.0, HealthZone.OPTIMAL),
                ZoneBand(25.0, 30.0, HealthZone.WARNING),
                ZoneBand(30.0, Double.POSITIVE_INFINITY, HealthZone.CRITICAL),
            ),
            bmiZoneBands(),
        )
    }

    @Test
    fun `body fat chart bands use the same female boundaries as the canonical assessment`() {
        assertEquals(
            listOf(
                ZoneBand(Double.NEGATIVE_INFINITY, 10.0, HealthZone.WARNING),
                ZoneBand(10.0, 14.0, HealthZone.NEUTRAL),
                ZoneBand(14.0, 21.0, HealthZone.OPTIMAL),
                ZoneBand(21.0, 25.0, HealthZone.OPTIMAL),
                ZoneBand(25.0, 32.0, HealthZone.NEUTRAL),
                ZoneBand(32.0, Double.POSITIVE_INFINITY, HealthZone.CRITICAL),
            ),
            bodyFatZoneBands(PhysiologyProfile.ACTIVE, Gender.FEMALE),
        )
    }

    @Test
    fun `fixed profile body fat chart bands preserve endpoint inclusion`() {
        val bands = bodyFatZoneBands(PhysiologyProfile.ACTIVE, null)

        assertEquals(HealthZone.NEUTRAL, bands.zoneAt(10.0))
        assertEquals(HealthZone.OPTIMAL, bands.zoneAt(Math.nextUp(10.0)))
        assertEquals(HealthZone.OPTIMAL, bands.zoneAt(30.0))
        assertEquals(HealthZone.CRITICAL, bands.zoneAt(Math.nextUp(30.0)))
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
