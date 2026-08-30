package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeSet
import javax.inject.Inject
import kotlin.math.pow

class Generate24hResidualFatigueCurveUseCase
    @Inject
    constructor() {
        companion object {
            const val SAMPLES_PER_DAY = 96
            const val STEP_MINUTES = 15
            const val MILLIS_PER_MINUTE = 60 * 1000L
            const val MILLIS_PER_HOUR = 3600 * 1000.0
        }

        fun execute(
            selectedDate: LocalDate,
            zoneId: ZoneId,
            config: ResidualFatigueConfig,
            retainedWorkouts: List<FatigueWorkoutInput>,
        ): List<FatigueCurvePoint> {
            val startZdt = selectedDate.atStartOfDay(zoneId)
            val dayStartMs = startZdt.toInstant().toEpochMilli()
            val dayEndMs = startZdt.plusDays(1).toInstant().toEpochMilli()

            val sampleTimes = TreeSet<Long>()
            for (i in 0 until SAMPLES_PER_DAY) {
                sampleTimes.add(dayStartMs + i * STEP_MINUTES * MILLIS_PER_MINUTE)
            }
            for (w in retainedWorkouts) {
                if (w.endTimeMs in dayStartMs until dayEndMs) {
                    sampleTimes.add(w.endTimeMs)
                }
            }

            val sortedWorkouts =
                retainedWorkouts
                    .filter { it.trimp > 0f }
                    .sortedWith(
                        compareBy<FatigueWorkoutInput> { it.endTimeMs }.thenBy { it.workoutId },
                    )

            val halfLifeMs = config.halfLifeHours.toDouble() * MILLIS_PER_HOUR
            val gain = config.fatigueGain.toDouble()
            val isEnabled = config.enabled && halfLifeMs > 0.0

            return sampleTimes.map { t ->
                var sum = 0.0
                if (isEnabled) {
                    for (w in sortedWorkouts) {
                        if (w.endTimeMs <= t) {
                            val deltaMs = (t - w.endTimeMs).toDouble()
                            sum += gain * w.trimp * 2.0.pow(-deltaMs / halfLifeMs)
                        } else {
                            break
                        }
                    }
                }
                val minutesOfDay = ((t - dayStartMs) / MILLIS_PER_MINUTE.toDouble()).toFloat()
                FatigueCurvePoint(
                    timestampMs = t,
                    timeMinutesOfDay = minutesOfDay,
                    fatigueValue = sum.toFloat(),
                )
            }
        }

        fun evaluateAt(
            timestampMs: Long,
            config: ResidualFatigueConfig,
            retainedWorkouts: List<FatigueWorkoutInput>,
        ): Float {
            val halfLifeMs = config.halfLifeHours.toDouble() * MILLIS_PER_HOUR
            if (!config.enabled || halfLifeMs <= 0.0) return 0f
            val gain = config.fatigueGain.toDouble()
            var sum = 0.0
            for (w in retainedWorkouts) {
                if (w.endTimeMs <= timestampMs && w.trimp > 0f) {
                    val deltaMs = (timestampMs - w.endTimeMs).toDouble()
                    sum += gain * w.trimp * 2.0.pow(-deltaMs / halfLifeMs)
                }
            }
            return sum.toFloat()
        }
    }
