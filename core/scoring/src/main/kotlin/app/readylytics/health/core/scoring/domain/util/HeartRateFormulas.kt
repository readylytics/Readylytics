package app.readylytics.health.core.scoring.domain.util

import app.readylytics.health.core.model.domain.preferences.UserPreferences

object HeartRateFormulas {
    /**
     * Estimates maximum heart rate using Tanaka formula: 208 - 0.7*age.
     * REF: Tanaka et al. 2001 meta-analysis of 351 studies (n=18,712).
     * More accurate than 220-age, validated across sex/fitness/ethnicity.
     *
     * The result is deliberately TRUNCATED (rounded down): age 35 → 183.5 → 183 (SCORE-104,
     * decided OD-3). This is load-bearing — hrMax is the hrR denominator in every TRIMP model,
     * so switching to roundToInt() would shift every auto-hrMax user's historical training load.
     * ABOUT.md, docs/about.md and `about_glossary_hrmax` document it; HeartRateFormulasTest pins it.
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
