package app.readylytics.health.domain.circadian

import app.readylytics.health.data.preferences.PhysiologyProfile

class ShiftWorkerCircadianStrategy : CircadianConsistencyStrategy {
    override fun determineThreshold(
        profile: PhysiologyProfile,
        override: Int?,
    ): Int {
        // For shift workers, disable standard rolling-anchor consistency checking by default
        // but allow manual override if provided.
        return override ?: Int.MAX_VALUE
    }
}
