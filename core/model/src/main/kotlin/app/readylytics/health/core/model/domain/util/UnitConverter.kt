package app.readylytics.health.core.model.domain.util

import app.readylytics.health.domain.preferences.UnitSystem
import kotlin.math.floor
import kotlin.math.roundToInt

object UnitConverter {
    // Conversion factors
    const val KG_TO_LBS = 2.20462f
    const val LBS_TO_KG = 0.453592f
    const val CM_TO_INCHES = 0.393701f
    const val INCHES_TO_CM = 2.54f
    const val CELSIUS_TO_FAHRENHEIT_MULTIPLIER = 9f / 5f
    const val CELSIUS_TO_FAHRENHEIT_OFFSET = 32f
    const val KM_TO_MI = 0.621371f
    const val METERS_TO_FEET = 3.28084f
    const val MI_PER_KM = 1.609344f
    const val PACE_CAP_MIN_PER_KM = 20f

    // Height conversions - returns raw values and format info for UI layer to apply i18n
    data class HeightDisplay(
        val value: String,
        val unit: String,
    )

    fun heightCmToDisplay(
        heightCm: Float?,
        unitSystem: UnitSystem,
    ): HeightDisplay =
        when {
            heightCm == null -> HeightDisplay("—", "")
            unitSystem == UnitSystem.METRIC -> HeightDisplay("${heightCm.toInt()}", "unit_metric_cm")
            else -> {
                val totalInches = heightCm / INCHES_TO_CM
                val feet = floor(totalInches / 12f).toInt()
                val inches = (totalInches % 12f).toInt()
                HeightDisplay("$feet$inches", "height_imperial_format")
            }
        }

    // Weight conversions - returns raw values and format info for UI layer to apply i18n
    data class WeightDisplay(
        val value: String,
        val unit: String,
    )

    fun weightKgToDisplay(
        weightKg: Float?,
        unitSystem: UnitSystem,
    ): WeightDisplay =
        when {
            weightKg == null -> WeightDisplay("—", "")
            unitSystem == UnitSystem.METRIC -> WeightDisplay("%.1f".format(weightKg), "unit_metric_kg")
            else -> WeightDisplay("%.1f".format(weightKg * KG_TO_LBS), "unit_imperial_lbs")
        }

    fun weightKgToDisplayShort(
        weightKg: Float?,
        unitSystem: UnitSystem,
    ): WeightDisplay =
        when {
            weightKg == null -> WeightDisplay("—", "")
            unitSystem == UnitSystem.METRIC -> WeightDisplay("${weightKg.toInt()}", "unit_metric_kg")
            else -> WeightDisplay("${(weightKg * KG_TO_LBS).toInt()}", "unit_imperial_lbs")
        }

    /** Absolute temperature conversion (applies the 32° offset for imperial). */
    fun celsiusToDisplayTemperature(
        celsius: Float,
        unitSystem: UnitSystem,
    ): Float =
        when (unitSystem) {
            UnitSystem.METRIC -> celsius
            UnitSystem.IMPERIAL -> celsius * CELSIUS_TO_FAHRENHEIT_MULTIPLIER + CELSIUS_TO_FAHRENHEIT_OFFSET
        }

    /** Temperature *difference* conversion (no offset — a 1°C delta is an 1.8°F delta). */
    fun celsiusDeltaToDisplayDelta(
        celsiusDelta: Float,
        unitSystem: UnitSystem,
    ): Float =
        when (unitSystem) {
            UnitSystem.METRIC -> celsiusDelta
            UnitSystem.IMPERIAL -> celsiusDelta * CELSIUS_TO_FAHRENHEIT_MULTIPLIER
        }

    fun formatDistance(
        meters: Float,
        unitSystem: UnitSystem,
    ): String =
        distanceParts(meters, unitSystem)?.let { "${it.value} ${it.unit}" } ?: "—"

    /** Numeric value and display unit token, for UIs that place the unit in its own slot. */
    data class MetricParts(
        val value: String,
        val unit: String,
    )

    fun distanceParts(
        meters: Float,
        unitSystem: UnitSystem,
    ): MetricParts? {
        if (meters <= 0f) return null
        return when (unitSystem) {
            UnitSystem.METRIC ->
                if (meters < 1000f) MetricParts("%.0f".format(meters), "m")
                else MetricParts("%.1f".format(meters / 1000f), "km")
            UnitSystem.IMPERIAL -> {
                val miles = meters * KM_TO_MI / 1000f
                if (miles < 0.1f) MetricParts("%.0f".format(meters * METERS_TO_FEET), "ft")
                else MetricParts("%.1f".format(miles), "mi")
            }
        }
    }

    fun formatSpeed(
        kmh: Float,
        unitSystem: UnitSystem,
    ): String =
        speedParts(kmh, unitSystem)?.let { "${it.value} ${it.unit}" } ?: "—"

    fun speedParts(
        kmh: Float,
        unitSystem: UnitSystem,
    ): MetricParts? {
        if (kmh <= 0f) return null
        return when (unitSystem) {
            UnitSystem.METRIC -> MetricParts("%.1f".format(kmh), "km/h")
            UnitSystem.IMPERIAL -> MetricParts("%.1f".format(kmh * KM_TO_MI), "mph")
        }
    }

    fun formatPace(
        minKm: Float,
        unitSystem: UnitSystem,
    ): String =
        paceParts(minKm, unitSystem)?.let { "${it.value} ${it.unit}" } ?: "—"

    fun paceParts(
        minKm: Float,
        unitSystem: UnitSystem,
    ): MetricParts? {
        if (minKm <= 0f) return null
        val capped = minKm.coerceAtMost(PACE_CAP_MIN_PER_KM)
        val minPerUnit =
            when (unitSystem) {
                UnitSystem.METRIC -> capped
                UnitSystem.IMPERIAL -> capped * MI_PER_KM
            }
        var minutes = minPerUnit.toInt()
        var seconds = ((minPerUnit - minutes) * 60f).roundToInt()
        if (seconds == 60) {
            minutes += 1
            seconds = 0
        }
        val unit =
            when (unitSystem) {
                UnitSystem.METRIC -> "min/km"
                UnitSystem.IMPERIAL -> "min/mi"
            }
        return MetricParts("$minutes:${seconds.toString().padStart(2, '0')}", unit)
    }

    fun formatElevation(
        meters: Float,
        unitSystem: UnitSystem,
    ): String =
        elevationParts(meters, unitSystem)?.let { "${it.value} ${it.unit}" } ?: "—"

    fun elevationParts(
        meters: Float,
        unitSystem: UnitSystem,
    ): MetricParts? {
        // 0 m is a real measurement (a genuinely flat route), not missing data -- only negative
        // and non-finite values mean "unavailable".
        if (meters < 0f || meters.isNaN() || meters.isInfinite()) return null
        if (meters > 15_000f) return null
        return when (unitSystem) {
            UnitSystem.METRIC -> MetricParts("%.0f".format(meters), "m")
            UnitSystem.IMPERIAL -> MetricParts("%.0f".format(meters * METERS_TO_FEET), "ft")
        }
    }
}
