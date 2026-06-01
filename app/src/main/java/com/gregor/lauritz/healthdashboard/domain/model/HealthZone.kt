package com.gregor.lauritz.healthdashboard.domain.model

import com.gregor.lauritz.healthdashboard.data.preferences.Gender

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

// AHA systolic thresholds (static)
fun systolicZoneBands(): List<ZoneBand> =
    listOf(
        ZoneBand(Double.NEGATIVE_INFINITY, 120.0, HealthZone.OPTIMAL),
        ZoneBand(120.0, 130.0, HealthZone.NEUTRAL),
        ZoneBand(130.0, 140.0, HealthZone.WARNING),
        ZoneBand(140.0, Double.POSITIVE_INFINITY, HealthZone.CRITICAL),
    )

// AHA diastolic thresholds (static)
fun diastolicZoneBands(): List<ZoneBand> =
    listOf(
        ZoneBand(Double.NEGATIVE_INFINITY, 80.0, HealthZone.OPTIMAL),
        ZoneBand(80.0, 90.0, HealthZone.NEUTRAL),
        ZoneBand(90.0, 100.0, HealthZone.WARNING),
        ZoneBand(100.0, Double.POSITIVE_INFINITY, HealthZone.CRITICAL),
    )

// WHO BMI thresholds (static)
fun bmiZoneBands(): List<ZoneBand> =
    listOf(
        ZoneBand(Double.NEGATIVE_INFINITY, 18.5, HealthZone.CRITICAL),
        ZoneBand(18.5, 25.0, HealthZone.OPTIMAL),
        ZoneBand(25.0, 30.0, HealthZone.NEUTRAL),
        ZoneBand(30.0, 35.0, HealthZone.WARNING),
        ZoneBand(35.0, Double.POSITIVE_INFINITY, HealthZone.CRITICAL),
    )

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

// ACE body fat % thresholds — age/gender adjusted
fun bodyFatZoneBands(
    age: Int,
    gender: Gender,
): List<ZoneBand> {
    val optMin: Double
    val optMax: Double
    val neutMax: Double
    val warnMax: Double
    when {
        gender == Gender.MALE && age < 40 -> {
            optMin = 8.0
            optMax = 19.0
            neutMax = 24.0
            warnMax = 29.0
        }
        gender == Gender.MALE && age < 60 -> {
            optMin = 11.0
            optMax = 21.0
            neutMax = 27.0
            warnMax = 32.0
        }
        gender == Gender.MALE -> {
            optMin = 13.0
            optMax = 24.0
            neutMax = 29.0
            warnMax = 34.0
        }
        gender == Gender.FEMALE && age < 40 -> {
            optMin = 20.0
            optMax = 32.0
            neutMax = 36.0
            warnMax = 41.0
        }
        gender == Gender.FEMALE && age < 60 -> {
            optMin = 23.0
            optMax = 33.0
            neutMax = 39.0
            warnMax = 44.0
        }
        gender == Gender.FEMALE -> {
            optMin = 24.0
            optMax = 35.0
            neutMax = 41.0
            warnMax = 46.0
        }
        else -> return emptyList()
    }
    return listOf(
        ZoneBand(Double.NEGATIVE_INFINITY, optMin, HealthZone.CRITICAL),
        ZoneBand(optMin, optMax, HealthZone.OPTIMAL),
        ZoneBand(optMax, neutMax, HealthZone.NEUTRAL),
        ZoneBand(neutMax, warnMax, HealthZone.WARNING),
        ZoneBand(warnMax, Double.POSITIVE_INFINITY, HealthZone.CRITICAL),
    )
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
