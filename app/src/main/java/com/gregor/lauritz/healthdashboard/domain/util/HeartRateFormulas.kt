package com.gregor.lauritz.healthdashboard.domain.util

object HeartRateFormulas {
    /**
     * Estimates maximum heart rate using Tanaka formula: 208 - 0.7*age.
     * REF: Tanaka et al. 2001 meta-analysis of 351 studies (n=18,712).
     * More accurate than 220-age, validated across sex/fitness/ethnicity.
     */
    fun estimateMaxHr(ageYears: Int): Int = (208 - 0.7 * ageYears).toInt()
}
