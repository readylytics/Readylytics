package com.gregor.lauritz.healthdashboard.domain.display

import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.domain.util.UnitConverter

object MetricFormatter {
    fun formatWeight(
        kg: Float,
        unit: UnitSystem,
    ): String {
        if (kg <= 0f) return "—"
        return when (unit) {
            UnitSystem.METRIC -> "%.1f kg".format(kg)
            UnitSystem.IMPERIAL -> "%.1f lbs".format(kg * UnitConverter.KG_TO_LBS)
        }
    }

    fun formatWeightNumericOnly(
        kg: Float,
        unit: UnitSystem,
    ): String {
        if (kg <= 0f) return "—"
        return when (unit) {
            UnitSystem.METRIC -> "%.1f".format(kg)
            UnitSystem.IMPERIAL -> "%.1f".format(kg * UnitConverter.KG_TO_LBS)
        }
    }

    fun formatBodyFat(percent: Float): String {
        if (percent <= 0f) return "—"
        return "%.1f%%".format(percent)
    }

    fun formatBodyFatNumericOnly(percent: Float): String {
        if (percent <= 0f) return "—"
        return "%.1f".format(percent)
    }

    fun formatBloodPressure(
        systolic: Int,
        diastolic: Int,
    ): String {
        if (systolic <= 0 || diastolic <= 0) return "—"
        return "$systolic/$diastolic"
    }

    fun formatZonePercent(fraction: Float): String {
        if (fraction < 0f) return "—"
        val pct = (fraction * 100f).toInt().coerceIn(0, 100)
        return "$pct%"
    }

    fun formatBmi(bmi: Float): String {
        if (bmi <= 0f) return "—"
        return "%.1f".format(bmi)
    }
}
