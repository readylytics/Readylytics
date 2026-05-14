package com.gregor.lauritz.healthdashboard.domain.util

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences

object HeartRateFormulas {
    /**
     * Estimates maximum heart rate using Tanaka formula: 208 - 0.7*age.
     * REF: Tanaka et al. 2001 meta-analysis of 351 studies (n=18,712).
     * More accurate than 220-age, validated across sex/fitness/ethnicity.
     */
    fun estimateMaxHr(ageYears: Int): Int = (208 - 0.7 * ageYears).toInt()

    /**
     * Resolves effective max heart rate from user preferences.
     * Uses auto-calculated Tanaka formula if enabled, otherwise manual override.
     * Single source of truth for HR max across scoring calculations.
     */
    fun resolveMaxHeartRate(prefs: UserPreferences): Float =
        if (prefs.autoCalculateMaxHr) {
            estimateMaxHr(prefs.age).toFloat()
        } else {
            prefs.maxHeartRate.toFloat()
        }
}
