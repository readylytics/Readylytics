package com.gregor.lauritz.healthdashboard.domain.validation

import java.util.Calendar

object SettingsValidators {
    val BIRTHDAY_DAY_RULE = IntRangeRule(1, 31, "Day: 1–31")
    val BIRTHDAY_MONTH_RULE = IntRangeRule(1, 12, "Month: 1–12")

    val BIRTHDAY_YEAR_RULE: ValidationRule<String>
        get() {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val maxYear = currentYear - 1
            return IntRangeRule(1900, maxYear, "Year: 1900–$maxYear")
        }

    val HRV_BASELINE_RULE = IntRangeRule(1, 500, "HRV: 1–500 ms")
    val RHR_BASELINE_RULE = IntRangeRule(30, 100, "RHR: 30–100 bpm")

    val HEART_RATE_RULE = IntRangeRule(1, 220, "HR: 1–220 bpm")
}
