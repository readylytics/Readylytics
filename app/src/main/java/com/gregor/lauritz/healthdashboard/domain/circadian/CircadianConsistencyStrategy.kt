package com.gregor.lauritz.healthdashboard.domain.circadian

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile

interface CircadianConsistencyStrategy {
    fun determineThreshold(
        profile: PhysiologyProfile,
        override: Int?,
    ): Int
}
