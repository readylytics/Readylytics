package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TreeSet
import javax.inject.Inject
import kotlin.math.pow

class GenerateResidualFatigueCurveUseCase
    @Inject
    constructor() {
        companion object {
            const val SAMPLES_PER_DAY = 96
            const val STEP_MINUTES = 15
            const val MILLIS_PER_MINUTE = 60 * 1000L
            const val MILLIS_PER_HOUR = 3600 * 1000.0
        }

        fun execute(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
            config: ResidualFatigueConfig,
            retainedWorkouts: List<FatigueWorkoutInput>,
        ): List<FatigueCurvePoint> {
            val startZdt = startDate.atStartOfDay(zoneId)
            val rangeStartMs = startZdt.toInstant().toEpochMilli()
            val rangeEndMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            val totalDays = ChronoUnit.DAYS.between(startDate, endDate.plusDays(1)).toInt()

            val sampleTimes = TreeSet<Long>()
            val totalGridPoints = totalDays * SAMPLES_PER_DAY
            for (i in 0 until totalGridPoints) {
                sampleTimes.add(rangeStartMs + i * STEP_MINUTES * MILLIS_PER_MINUTE)
            }
            for (w in retainedWorkouts) {
                if (w.endTimeMs in rangeStartMs until rangeEndMs) {
                    sampleTimes.add(w.endTimeMs)
                }
            }

            if (!config.enabled || config.halfLifeHours <= 0f) {
                return sampleTimes.map { t ->
                    val minutesFromStart = ((t - rangeStartMs) / MILLIS_PER_MINUTE.toDouble()).toFloat()
                    FatigueCurvePoint(
                        timestampMs = t,
                        timeMinutesFromStart = minutesFromStart,
                        fatigueValue = 0f,
                    )
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

            return sampleTimes.map { t ->
                var sum = 0.0
                for (w in sortedWorkouts) {
                    if (w.endTimeMs <= t) {
                        val deltaMs = (t - w.endTimeMs).toDouble()
                        sum += gain * w.trimp * 2.0.pow(-deltaMs / halfLifeMs)
                    } else {
                        break
                    }
                }
                val minutesFromStart = ((t - rangeStartMs) / MILLIS_PER_MINUTE.toDouble()).toFloat()
                FatigueCurvePoint(
                    timestampMs = t,
                    timeMinutesFromStart = minutesFromStart,
                    fatigueValue = sum.toFloat(),
                )
            }
        }

        fun evaluateAt(
            timestampMs: Long,
            config: ResidualFatigueConfig,
            retainedWorkouts: List<FatigueWorkoutInput>,
        ): Float {
            if (!config.enabled || config.halfLifeHours <= 0f) return 0f
            val halfLifeMs = config.halfLifeHours.toDouble() * MILLIS_PER_HOUR
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
