package app.readylytics.health.domain.util

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
    ): String {
        if (meters <= 0f) return "—"
        return when (unitSystem) {
            UnitSystem.METRIC ->
                if (meters < 1000f) "%.0f m".format(meters)
                else "%.1f km".format(meters / 1000f)
            UnitSystem.IMPERIAL -> {
                val miles = meters * KM_TO_MI / 1000f
                if (miles < 0.1f) "%.0f ft".format(meters * METERS_TO_FEET)
                else "%.1f mi".format(miles)
            }
        }
    }

    fun formatSpeed(
        kmh: Float,
        unitSystem: UnitSystem,
    ): String {
        if (kmh <= 0f) return "—"
        return when (unitSystem) {
            UnitSystem.METRIC -> "%.1f km/h".format(kmh)
            UnitSystem.IMPERIAL -> "%.1f mph".format(kmh * KM_TO_MI)
        }
    }

    fun formatPace(
        minKm: Float,
        unitSystem: UnitSystem,
    ): String {
        if (minKm <= 0f) return "—"
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
                UnitSystem.METRIC -> "km"
                UnitSystem.IMPERIAL -> "mi"
            }
        return "$minutes:${seconds.toString().padStart(2, '0')} /$unit"
    }

    fun formatElevation(
        meters: Float,
        unitSystem: UnitSystem,
    ): String {
        if (meters <= 0f || meters.isNaN() || meters.isInfinite()) return "—"
        val bounded = meters.coerceIn(0f, 15_000f)
        return when (unitSystem) {
            UnitSystem.METRIC -> "%.0f m".format(bounded)
            UnitSystem.IMPERIAL -> "%.0f ft".format(bounded * METERS_TO_FEET)
        }
    }
}
