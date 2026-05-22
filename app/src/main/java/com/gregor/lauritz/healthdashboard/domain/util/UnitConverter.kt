package com.gregor.lauritz.healthdashboard.domain.util

import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import kotlin.math.floor

object UnitConverter {
    // Conversion factors
    const val KG_TO_LBS = 2.20462f
    const val LBS_TO_KG = 0.453592f
    const val CM_TO_INCHES = 0.393701f
    const val INCHES_TO_CM = 2.54f

    // Height conversions - returns raw values and format info for UI layer to apply i18n
    data class HeightDisplay(
        val value: String,
        val unit: String,
    )

    fun heightCmToDisplay(heightCm: Float?, unitSystem: UnitSystem): HeightDisplay = when {
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

    fun weightKgToDisplay(weightKg: Float?, unitSystem: UnitSystem): WeightDisplay = when {
        weightKg == null -> WeightDisplay("—", "")
        unitSystem == UnitSystem.METRIC -> WeightDisplay("%.1f".format(weightKg), "unit_metric_kg")
        else -> WeightDisplay("%.1f".format(weightKg * KG_TO_LBS), "unit_imperial_lbs")
    }

    fun weightKgToDisplayShort(weightKg: Float?, unitSystem: UnitSystem): WeightDisplay = when {
        weightKg == null -> WeightDisplay("—", "")
        unitSystem == UnitSystem.METRIC -> WeightDisplay("${weightKg.toInt()}", "unit_metric_kg")
        else -> WeightDisplay("${(weightKg * KG_TO_LBS).toInt()}", "unit_imperial_lbs")
    }
}
