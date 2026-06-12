package app.readylytics.health.domain.circadian

import app.readylytics.health.data.preferences.PhysiologyProfile

interface CircadianConsistencyStrategy {
    fun determineThreshold(
        profile: PhysiologyProfile,
        override: Int?,
    ): Int
}
