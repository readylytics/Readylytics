package com.gregor.lauritz.healthdashboard.domain.circadian

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile

object CircadianStrategyFactory {
    fun getStrategy(profile: PhysiologyProfile): CircadianConsistencyStrategy {
        return when (profile) {
            PhysiologyProfile.SHIFT_WORKER -> ShiftWorkerCircadianStrategy()
            else -> RegularUserCircadianStrategy()
        }
    }
}
