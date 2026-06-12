package app.readylytics.health.ui.workouts.mappers

import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.ui.workouts.HeartRatePoint
import java.time.Instant
import java.time.temporal.ChronoUnit

data class RecoveryMetrics(
    val hrr1Min: Int?,
    val hrr2Min: Int?,
    val hrr3Min: Int?,
)

object RecoveryMetricsMapper {
    fun mapRecoveryMetrics(
        samples: List<HeartRatePoint>,
        workoutEnd: Long,
        endHr: Int?,
    ): RecoveryMetrics {
        if (endHr == null) {
            return RecoveryMetrics(null, null, null)
        }

        val workoutEndInstant = Instant.ofEpochMilli(workoutEnd)
        val recoverySamples = samples.filter { it.timestamp > workoutEndInstant }

        val hrr1Min = findRecoveryHr(recoverySamples, workoutEndInstant, 1)
        val hrr2Min = findRecoveryHr(recoverySamples, workoutEndInstant, 2)
        val hrr3Min = findRecoveryHr(recoverySamples, workoutEndInstant, 3)

        return RecoveryMetrics(
            hrr1Min = if (hrr1Min != null) endHr - hrr1Min else null,
            hrr2Min = if (hrr2Min != null) endHr - hrr2Min else null,
            hrr3Min = if (hrr3Min != null) endHr - hrr3Min else null,
        )
    }

    private fun findRecoveryHr(
        samples: List<HeartRatePoint>,
        target: Instant,
        minutes: Int,
    ): Int? {
        val targetTime = target.plus(minutes.toLong(), ChronoUnit.MINUTES)
        val toleranceSeconds = ScoringConstants.Workout.HRR_TOLERANCE_SECONDS
        return samples
            .filter { sample ->
                val diff =
                    java.time.Duration
                        .between(sample.timestamp, targetTime)
                        .abs()
                        .seconds
                diff <= toleranceSeconds
            }.minByOrNull { sample ->
                java.time.Duration
                    .between(sample.timestamp, targetTime)
                    .abs()
                    .toMillis()
            }?.bpm
    }
}
