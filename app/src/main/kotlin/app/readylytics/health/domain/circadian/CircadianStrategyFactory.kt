package app.readylytics.health.domain.circadian

import app.readylytics.health.data.preferences.PhysiologyProfile

object CircadianStrategyFactory {
    fun getStrategy(profile: PhysiologyProfile): CircadianConsistencyStrategy =
        when (profile) {
            PhysiologyProfile.SHIFT_WORKER -> ShiftWorkerCircadianStrategy()
            else -> RegularUserCircadianStrategy()
        }
}
