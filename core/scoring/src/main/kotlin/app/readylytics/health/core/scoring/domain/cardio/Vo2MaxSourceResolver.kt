package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.data.preferences.Vo2MaxSourceMode
import javax.inject.Inject
import javax.inject.Singleton

data class Vo2MaxResolution(val vo2Max: Float?, val source: String?)

@Singleton
class Vo2MaxSourceResolver @Inject constructor() {
    fun resolve(
        mode: Vo2MaxSourceMode,
        wearableVo2Max: Float?,
        uthEstimatedVo2Max: Float?,
    ): Vo2MaxResolution =
        when (mode) {
            Vo2MaxSourceMode.AUTO ->
                if (wearableVo2Max != null) {
                    Vo2MaxResolution(wearableVo2Max, "WEARABLE")
                } else if (uthEstimatedVo2Max != null) {
                    Vo2MaxResolution(uthEstimatedVo2Max, "ESTIMATED_UTH")
                } else {
                    Vo2MaxResolution(null, null)
                }
            Vo2MaxSourceMode.WEARABLE_ONLY ->
                if (wearableVo2Max != null) {
                    Vo2MaxResolution(wearableVo2Max, "WEARABLE")
                } else {
                    Vo2MaxResolution(null, null)
                }
            Vo2MaxSourceMode.ESTIMATED_ONLY ->
                if (uthEstimatedVo2Max != null) {
                    Vo2MaxResolution(uthEstimatedVo2Max, "ESTIMATED_UTH")
                } else {
                    Vo2MaxResolution(null, null)
                }
        }
}
