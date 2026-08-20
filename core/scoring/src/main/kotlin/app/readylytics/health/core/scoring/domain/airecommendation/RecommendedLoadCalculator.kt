package app.readylytics.health.core.scoring.domain.airecommendation

import app.readylytics.health.domain.model.LoadContext
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassifier
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import javax.inject.Inject

enum class QualitativeLoad { LIGHT, MODERATE, NORMAL, HIGH }

class RecommendedLoadCalculator
    @Inject
    constructor(
        private val workoutLoadClassifier: WorkoutLoadClassifier,
    ) {
        fun compute(loadContext: LoadContext?, todayTrimp: Float?): String? {
            val base = baseFor(loadContext) ?: return null
            val steps = downgradeStepsFor(todayTrimp)
            val resolved = (base.ordinal - steps).coerceAtLeast(QualitativeLoad.LIGHT.ordinal)
            return QualitativeLoad.entries[resolved].name
        }

        private fun baseFor(loadContext: LoadContext?): QualitativeLoad? =
            when (loadContext) {
                LoadContext.BELOW_TYPICAL -> QualitativeLoad.HIGH
                LoadContext.SWEET_SPOT -> QualitativeLoad.NORMAL
                LoadContext.ELEVATED -> QualitativeLoad.MODERATE
                LoadContext.HIGH -> QualitativeLoad.LIGHT
                LoadContext.UNKNOWN, null -> null
            }

        private fun downgradeStepsFor(todayTrimp: Float?): Int =
            when (workoutLoadClassifier.classifyBaseLoad((todayTrimp ?: 0f).toDouble())) {
                WorkoutLoadLevel.VERY_LIGHT, null -> 0
                WorkoutLoadLevel.LIGHT -> 1
                WorkoutLoadLevel.MODERATE -> 2
                WorkoutLoadLevel.HARD, WorkoutLoadLevel.VERY_HARD -> 3
            }
    }
