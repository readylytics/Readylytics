package com.gregor.lauritz.healthdashboard.domain.util

object HeartRateFormulas {
    /**
     * Estimates maximum heart rate based on age using the common formula: 220 - age.
     */
    fun estimateMaxHr(ageYears: Int): Int = 220 - ageYears
}
