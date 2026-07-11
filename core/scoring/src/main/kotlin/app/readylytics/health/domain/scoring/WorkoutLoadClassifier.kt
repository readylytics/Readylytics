package app.readylytics.health.domain.scoring

import javax.inject.Inject

data class WorkoutLoadThresholds(
    val lightMinTrimp: Double = 30.0,
    val moderateMinTrimp: Double = 70.0,
    val hardMinTrimp: Double = 140.0,
    val veryHardMinTrimp: Double = 200.0,
    val moderatePromotionMinTrimp: Double = 90.0,
    val hardPromotionMinDensity: Double = 1.75,
    val veryHardPromotionMinTrimp: Double = 140.0,
    val veryHardPromotionMinDensity: Double = 2.25,
)

data class WorkoutLoadClassification(
    val totalTrimp: Double,
    val trimpPerMinute: Double?,
    val baseLoad: WorkoutLoadLevel,
    val intensity: WorkoutIntensityLevel?,
    val finalLoad: WorkoutLoadLevel,
    val wasPromoted: Boolean,
)

class WorkoutLoadClassifier
    @Inject
    constructor() {
        private val thresholds = WorkoutLoadThresholds()

        fun classifyBaseLoad(totalTrimp: Double): WorkoutLoadLevel? {
            if (!totalTrimp.isFinite() || totalTrimp < 0.0) return null

            return when {
                totalTrimp < thresholds.lightMinTrimp -> WorkoutLoadLevel.VERY_LIGHT
                totalTrimp < thresholds.moderateMinTrimp -> WorkoutLoadLevel.LIGHT
                totalTrimp < thresholds.hardMinTrimp -> WorkoutLoadLevel.MODERATE
                totalTrimp < thresholds.veryHardMinTrimp -> WorkoutLoadLevel.HARD
                else -> WorkoutLoadLevel.VERY_HARD
            }
        }

        fun classifyIntensity(trimpPerMinute: Double): WorkoutIntensityLevel? {
            if (!trimpPerMinute.isFinite() || trimpPerMinute < 0.0) return null

            return when {
                trimpPerMinute < ScoringConstants.TrimpIntensityThresholds.VERY_LIGHT_MAX ->
                    WorkoutIntensityLevel.VERY_LIGHT
                trimpPerMinute < ScoringConstants.TrimpIntensityThresholds.LIGHT_MAX ->
                    WorkoutIntensityLevel.LIGHT
                trimpPerMinute < ScoringConstants.TrimpIntensityThresholds.MODERATE_MAX ->
                    WorkoutIntensityLevel.MODERATE
                trimpPerMinute < ScoringConstants.TrimpIntensityThresholds.HARD_MAX ->
                    WorkoutIntensityLevel.HARD
                else -> WorkoutIntensityLevel.VERY_HARD
            }
        }

        fun classify(
            totalTrimp: Double,
            trimpPerMinute: Double?,
        ): WorkoutLoadClassification? {
            val baseLoad = classifyBaseLoad(totalTrimp) ?: return null
            val intensity = trimpPerMinute?.let(::classifyIntensity)
            val finalLoad =
                when {
                    baseLoad == WorkoutLoadLevel.MODERATE &&
                        totalTrimp >= thresholds.moderatePromotionMinTrimp &&
                        trimpPerMinute != null &&
                        trimpPerMinute.isFinite() &&
                        trimpPerMinute >= thresholds.hardPromotionMinDensity -> WorkoutLoadLevel.HARD

                    baseLoad == WorkoutLoadLevel.HARD &&
                        totalTrimp >= thresholds.veryHardPromotionMinTrimp &&
                        trimpPerMinute != null &&
                        trimpPerMinute.isFinite() &&
                        trimpPerMinute >= thresholds.veryHardPromotionMinDensity ->
                        WorkoutLoadLevel.VERY_HARD

                    else -> baseLoad
                }

            return WorkoutLoadClassification(
                totalTrimp = totalTrimp,
                trimpPerMinute = trimpPerMinute,
                baseLoad = baseLoad,
                intensity = intensity,
                finalLoad = finalLoad,
                wasPromoted = finalLoad != baseLoad,
            )
        }
    }
