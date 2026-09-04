package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import javax.inject.Inject
import javax.inject.Singleton

data class Vo2MaxResolution(val vo2Max: Float?, val source: String?)

@Singleton
class Vo2MaxSourceResolver @Inject constructor() {
    fun resolve(
        mode: Vo2MaxSourceMode,
        wearableVo2Max: Float?,
        estimatedVo2Max: Float?,
        estimatedSource: String?,
    ): Vo2MaxResolution =
        when (mode) {
            Vo2MaxSourceMode.AUTO ->
                if (wearableVo2Max != null) {
                    Vo2MaxResolution(wearableVo2Max, SOURCE_WEARABLE)
                } else if (estimatedVo2Max != null) {
                    Vo2MaxResolution(estimatedVo2Max, estimatedSource)
                } else {
                    Vo2MaxResolution(null, null)
                }
            Vo2MaxSourceMode.WEARABLE_ONLY ->
                if (wearableVo2Max != null) {
                    Vo2MaxResolution(wearableVo2Max, SOURCE_WEARABLE)
                } else {
                    Vo2MaxResolution(null, null)
                }
            Vo2MaxSourceMode.ESTIMATED_ONLY ->
                if (estimatedVo2Max != null) {
                    Vo2MaxResolution(estimatedVo2Max, estimatedSource)
                } else {
                    Vo2MaxResolution(null, null)
                }
        }

    companion object {
        const val SOURCE_WEARABLE = "WEARABLE"
        const val SOURCE_ESTIMATED_UTH = "ESTIMATED_UTH"
        const val SOURCE_ESTIMATED_MATERKO_ADAPTED = "ESTIMATED_MATERKO_ADAPTED"
    }
}
