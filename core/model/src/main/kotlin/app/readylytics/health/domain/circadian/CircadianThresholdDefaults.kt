package app.readylytics.health.domain.circadian

import app.readylytics.health.domain.preferences.PhysiologyProfile

/**
 * Single source of truth for circadian-consistency thresholds.
 *
 * The per-profile default is the allowed bed/wake deviation (in minutes) before the
 * consistency score starts to decay. [resolveThreshold] is the *only* path the live score
 * uses: an explicit user override wins, otherwise the profile default applies. This replaces
 * the former strategy hierarchy (RegularUser/ShiftWorker strategies) and the flat
 * `consistencyThresholdMinutes` pref, neither of which reached the score.
 */
object CircadianThresholdDefaults {
    fun getProfileDefault(profile: PhysiologyProfile): Int =
        when (profile) {
            PhysiologyProfile.ATHLETE -> 20
            PhysiologyProfile.ACTIVE -> 30
            PhysiologyProfile.SEDENTARY -> 45
        }

    /**
     * Resolves the effective threshold for a day's scoring: the user [override] if present,
     * otherwise the [profile] default. Pure; the single resolver consumed by both the live
     * [app.readylytics.health.domain.scoring.CircadianConsistencyRepository] and the
     * diagnostic config in `ScoringConfigFactory`.
     */
    fun resolveThreshold(
        profile: PhysiologyProfile,
        override: Int?,
    ): Int = override ?: getProfileDefault(profile)
}
