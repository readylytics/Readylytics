package app.readylytics.health.domain.circadian

import app.readylytics.health.data.preferences.PhysiologyProfile

object CircadianThresholdDefaults {
    fun getProfileDefault(profile: PhysiologyProfile): Int =
        when (profile) {
            PhysiologyProfile.ATHLETE -> 20
            PhysiologyProfile.ACTIVE -> 30
            PhysiologyProfile.GENERAL -> 30
            PhysiologyProfile.SEDENTARY -> 45
            // SHIFT_WORKER: Only displayed if using standard rolling-anchor mode.
            // In within-week mode, threshold is disabled (Int.MAX_VALUE in strategy).
            PhysiologyProfile.SHIFT_WORKER -> 20
        }
}
