package com.gregor.lauritz.healthdashboard.domain.util

import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import kotlin.math.floor

object UnitConverter {
    // Height conversions
    fun heightCmToString(heightCm: Float?, unitSystem: UnitSystem): String = when {
        heightCm == null -> "—"
        unitSystem == UnitSystem.METRIC -> "${heightCm.toInt()} cm"
        else -> {
            val totalInches = heightCm / 2.54f
            val feet = floor(totalInches / 12f).toInt()
            val inches = (totalInches % 12f).toInt()
            "'$feet\"$inches"
        }
    }

    fun heightCmToStringShort(heightCm: Float?, unitSystem: UnitSystem): String = when {
        heightCm == null -> "—"
        unitSystem == UnitSystem.METRIC -> "${heightCm.toInt()} cm"
        else -> {
            val totalInches = heightCm / 2.54f
            val feet = floor(totalInches / 12f).toInt()
            val inches = (totalInches % 12f).toInt()
            "$feet'$inches\""
        }
    }

    // Weight conversions
    fun weightKgToString(weightKg: Float?, unitSystem: UnitSystem): String = when {
        weightKg == null -> "—"
        unitSystem == UnitSystem.METRIC -> "%.1f kg".format(weightKg)
        else -> "%.1f lbs".format(weightKg * 2.20462f)
    }

    fun weightKgToStringShort(weightKg: Float?, unitSystem: UnitSystem): String = when {
        weightKg == null -> "—"
        unitSystem == UnitSystem.METRIC -> "${weightKg.toInt()} kg"
        else -> "${(weightKg * 2.20462f).toInt()} lbs"
    }

    // Conversion factors
    const val KG_TO_LBS = 2.20462f
    const val LBS_TO_KG = 0.453592f
    const val CM_TO_INCHES = 0.393701f
    const val INCHES_TO_CM = 2.54f
}
