package app.readylytics.health.domain.model

import app.readylytics.health.domain.preferences.Gender
import app.readylytics.health.domain.preferences.PhysiologyProfile

enum class HealthZone { OPTIMAL, NEUTRAL, WARNING, CRITICAL }

data class ZoneBand(
    val lowerBound: Double,
    val upperBound: Double,
    val zone: HealthZone,
)

// RHR — lower is better: below optimalMax=OPTIMAL, up to neutralMax=NEUTRAL, up to warningMax=WARNING, above=CRITICAL
fun rhrZoneBands(
    optimalMax: Float,
    neutralMax: Float,
    warningMax: Float,
): List<ZoneBand> =
    listOf(
        ZoneBand(Double.NEGATIVE_INFINITY, optimalMax.toDouble(), HealthZone.OPTIMAL),
        ZoneBand(optimalMax.toDouble(), neutralMax.toDouble(), HealthZone.NEUTRAL),
        ZoneBand(neutralMax.toDouble(), warningMax.toDouble(), HealthZone.WARNING),
        ZoneBand(warningMax.toDouble(), Double.POSITIVE_INFINITY, HealthZone.CRITICAL),
    )

// BMI chart bands are presentation metadata derived from the canonical assessment seam.
fun bmiZoneBands(): List<ZoneBand> =
    BodyCompositionAssessment.bmiReference.bands.map { band ->
        ZoneBand(
            lowerBound = band.minimumInclusive?.toDouble() ?: Double.NEGATIVE_INFINITY,
            upperBound = band.maximumExclusive?.toDouble() ?: Double.POSITIVE_INFINITY,
            zone = band.status.toHealthZone(),
        )
    }

// Convert BMI zone bands to weight (kg) zone bands using height
fun weightZoneBands(heightCm: Float): List<ZoneBand> {
    if (heightCm <= 0f) return emptyList()
    val h = heightCm / 100.0
    val hSq = h * h
    return bmiZoneBands().map { band ->
        ZoneBand(
            lowerBound =
                if (band.lowerBound ==
                    Double.NEGATIVE_INFINITY
                ) {
                    Double.NEGATIVE_INFINITY
                } else {
                    band.lowerBound * hSq
                },
            upperBound =
                if (band.upperBound ==
                    Double.POSITIVE_INFINITY
                ) {
                    Double.POSITIVE_INFINITY
                } else {
                    band.upperBound * hSq
                },
            zone = band.zone,
        )
    }
}

// Body-fat chart bands are presentation metadata derived from the canonical assessment seam.
fun bodyFatZoneBands(
    physiologyProfile: PhysiologyProfile,
    gender: Gender?,
): List<ZoneBand> =
    BodyCompositionAssessment.bodyFatReference(physiologyProfile, gender).bands.map { band ->
        ZoneBand(
            lowerBound = band.minimumInclusive?.toDouble() ?: Double.NEGATIVE_INFINITY,
            upperBound = band.maximumExclusive?.toDouble() ?: Double.POSITIVE_INFINITY,
            zone = band.status.toHealthZone(),
        )
    }

private fun BmiStatus.toHealthZone(): HealthZone =
    when (this) {
        BmiStatus.Optimal -> HealthZone.OPTIMAL
        BmiStatus.Neutral -> HealthZone.NEUTRAL
        BmiStatus.Warning -> HealthZone.WARNING
        BmiStatus.Poor -> HealthZone.CRITICAL
    }

private fun BodyFatStatus.toHealthZone(): HealthZone =
    when (this) {
        BodyFatStatus.Optimal -> HealthZone.OPTIMAL
        BodyFatStatus.Neutral -> HealthZone.NEUTRAL
        BodyFatStatus.Warning -> HealthZone.WARNING
        BodyFatStatus.Poor -> HealthZone.CRITICAL
    }

// HRV — higher is better: above optimalMin=OPTIMAL, down to neutralMin=NEUTRAL, down to warningMin=WARNING, below=CRITICAL
fun hrvZoneBands(
    optimalMin: Float,
    neutralMin: Float,
    warningMin: Float,
): List<ZoneBand> =
    listOf(
        ZoneBand(Double.NEGATIVE_INFINITY, warningMin.toDouble(), HealthZone.CRITICAL),
        ZoneBand(warningMin.toDouble(), neutralMin.toDouble(), HealthZone.WARNING),
        ZoneBand(neutralMin.toDouble(), optimalMin.toDouble(), HealthZone.NEUTRAL),
        ZoneBand(optimalMin.toDouble(), Double.POSITIVE_INFINITY, HealthZone.OPTIMAL),
    )

fun spo2ZoneBands(): List<ZoneBand> =
    listOf(
        ZoneBand(Double.NEGATIVE_INFINITY, 90.0, HealthZone.CRITICAL),
        ZoneBand(90.0, 95.0, HealthZone.WARNING),
        ZoneBand(95.0, 98.0, HealthZone.NEUTRAL),
        ZoneBand(98.0, Double.POSITIVE_INFINITY, HealthZone.OPTIMAL),
    )
