package app.readylytics.health.core.model.domain.model

import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile

enum class HealthZone { OPTIMAL, NEUTRAL, WARNING, CRITICAL }

data class ZoneBand(
    val lowerBound: Double,
    val upperBound: Double,
    val zone: HealthZone,
    val includesMinimum: Boolean = true,
    val includesMaximum: Boolean = false,
)

data class BucketZoneBands(
    val startDayOffset: Int,
    val endDayOffset: Int,
    val bands: List<ZoneBand>,
)

// RHR — lower is better: below optimalMax=OPTIMAL, up to neutralMax=NEUTRAL, up to warningMax=WARNING, above=CRITICAL
fun rhrZoneBands(
    optimalMax: Float,
    neutralMax: Float,
    warningMax: Float,
): List<ZoneBand> =
    rhrZoneBandsForThresholds(
        optimalMax = optimalMax.toDouble(),
        warningMax = neutralMax.toDouble(),
        poorMax = warningMax.toDouble(),
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
            includesMinimum = band.includesMinimum,
            includesMaximum = band.includesMaximum,
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
    hrvZoneBandsForThresholds(
        optimalMin = optimalMin.toDouble(),
        warningMin = neutralMin.toDouble(),
        poorMin = warningMin.toDouble(),
    )

fun spo2ZoneBands(): List<ZoneBand> = spo2ReferenceZoneBands()
