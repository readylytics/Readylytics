package com.gregor.lauritz.healthdashboard.domain.circadian

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile

class RegularUserCircadianStrategy : CircadianConsistencyStrategy {
    override fun determineThreshold(profile: PhysiologyProfile, override: Int?): Int {
        return override ?: when (profile) {
            PhysiologyProfile.ATHLETE -> 20
            PhysiologyProfile.ACTIVE -> 30
            PhysiologyProfile.GENERAL -> 30
            PhysiologyProfile.SEDENTARY -> 45
            PhysiologyProfile.SHIFT_WORKER -> Int.MAX_VALUE // Shouldn't reach here for shift workers
        }
    }
}
