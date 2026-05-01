package com.gregor.lauritz.healthdashboard.domain.circadian

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile

class ShiftWorkerCircadianStrategy : CircadianConsistencyStrategy {
    override fun determineThreshold(profile: PhysiologyProfile, override: Int?): Int {
        // For shift workers, disable standard rolling-anchor consistency checking
        // Instead use within-week (day-of-week) regularity logic
        return Int.MAX_VALUE // Sentinel value indicating disabled standard threshold
    }
}
