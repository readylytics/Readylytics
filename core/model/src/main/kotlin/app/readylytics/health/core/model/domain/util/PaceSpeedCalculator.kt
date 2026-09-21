package app.readylytics.health.core.model.domain.util

import app.readylytics.health.core.model.domain.workouts.detail.ExerciseType
import app.readylytics.health.core.model.domain.workouts.detail.ExerciseTypeMapper

object PaceSpeedCalculator {
    // Foot-borne activities, reported as pace (min/km) rather than speed (km/h). Resolution of the
    // raw `exerciseType` string — numeric Health Connect id or symbolic name — is delegated to
    // [ExerciseTypeMapper] so this does not keep a second copy of the id table.
    private val PACE_ACTIVITIES =
        setOf(
            ExerciseType.RUNNING,
            ExerciseType.RUNNING_TREADMILL,
            ExerciseType.WALKING,
            ExerciseType.HIKING,
        )

    const val MAX_PACE_MIN_KM = 20.0
    const val PACE_CAP_MIN_PER_KM = MAX_PACE_MIN_KM

    fun isPaceActivity(exerciseType: String): Boolean = ExerciseTypeMapper.fromRaw(exerciseType) in PACE_ACTIVITIES

    fun speedMpsToPaceMinKm(speedMps: Double): Double {
        if (speedMps <= 0.05) return MAX_PACE_MIN_KM
        val minKm = (1000.0 / speedMps) / 60.0
        return minKm.coerceAtMost(MAX_PACE_MIN_KM)
    }

    fun speedMpsToSpeedKmh(speedMps: Double): Double = (speedMps * 3.6).coerceAtLeast(0.0)
}
