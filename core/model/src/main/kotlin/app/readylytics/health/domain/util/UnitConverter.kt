package app.readylytics.health.domain.util

import app.readylytics.health.domain.preferences.UnitSystem
import kotlin.math.floor

object UnitConverter {
    // Conversion factors
    const val KG_TO_LBS = 2.20462f
    const val LBS_TO_KG = 0.453592f
    const val CM_TO_INCHES = 0.393701f
    const val INCHES_TO_CM = 2.54f
    const val CELSIUS_TO_FAHRENHEIT_MULTIPLIER = 9f / 5f
    const val CELSIUS_TO_FAHRENHEIT_OFFSET = 32f

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
}
