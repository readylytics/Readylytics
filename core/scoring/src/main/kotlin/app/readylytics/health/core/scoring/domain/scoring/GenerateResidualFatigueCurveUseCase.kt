package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeSet
import javax.inject.Inject
import kotlin.math.pow

class GenerateResidualFatigueCurveUseCase
    @Inject
    constructor() {
        companion object {
            const val SAMPLES_PER_DAY = 96
            const val STEP_MINUTES = 15L
            const val MILLIS_PER_MINUTE = 60 * 1000L
            const val MILLIS_PER_HOUR = 3600 * 1000.0
        }

        /**
         * Samples the decay curve across `[startDate, endDate]` in [zoneId].
         *
         * [nowMs] truncates the curve: nothing past the present is drawn, because residual fatigue
         * after now is a projection, not a measurement. For a range ending on a past day the bound
         * is inert; for one ending today the last sample is [nowMs] itself, so the series ends
         * exactly at the current time rather than running on to midnight.
         */
        fun execute(
            startDate: LocalDate,
            endDate: LocalDate,
            zoneId: ZoneId,
            config: ResidualFatigueConfig,
            retainedWorkouts: List<FatigueWorkoutInput>,
            nowMs: Long = Long.MAX_VALUE,
        ): List<FatigueCurvePoint> {
            val startZdt = startDate.atStartOfDay(zoneId)
            val rangeStartMs = startZdt.toInstant().toEpochMilli()
            val rangeEndMs = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            // Exclusive upper bound of the plotted window: the range end, or the present if the
            // range has not finished yet.
            val lastSampleMs = minOf(rangeEndMs - 1, nowMs)

            val sampleTimes = TreeSet<Long>()
            // Step through the zone, not through raw millis. A fixed 15-minute * 96 * days grid
            // overruns the range end by an hour on a 23-hour DST day and stops an hour short on a
            // 25-hour one, which desynchronises timeMinutesFromStart from wall clock for every
            // tooltip and day-boundary axis label downstream.
            var cursor = startZdt
            var cursorMs = rangeStartMs
            while (cursorMs <= lastSampleMs) {
                sampleTimes.add(cursorMs)
                cursor = cursor.plusMinutes(STEP_MINUTES)
                cursorMs = cursor.toInstant().toEpochMilli()
            }
            if (nowMs in rangeStartMs..lastSampleMs) {
                sampleTimes.add(nowMs)
            }
            for (w in retainedWorkouts) {
                if (w.endTimeMs in rangeStartMs..lastSampleMs) {
                    sampleTimes.add(w.endTimeMs)
                }
            }

            if (config.halfLifeHours <= 0f) {
                return sampleTimes.map { t -> curvePoint(t, rangeStartMs, 0f) }
            }

            val sortedWorkouts =
                retainedWorkouts
                    .filter { it.trimp > 0f }
                    .sortedWith(
                        compareBy<FatigueWorkoutInput> { it.endTimeMs }.thenBy { it.workoutId },
                    )

            val halfLifeMs = config.halfLifeHours.toDouble() * MILLIS_PER_HOUR
            val gain = config.fatigueGain.toDouble()

            // Single pass over both ascending sequences. Re-summing every workout at every sample
            // is O(samples * workouts) — up to 672 samples times the user's entire workout history
            // on each emission. Decaying the running total between samples and folding in the
            // impulses that became due is the same algebra the persisted walk-forward uses
            // (ComputeResidualFatigueUseCase.advanceAccumulator), in O(samples + workouts).
            var accumulated = 0.0
            var previousSampleMs = Long.MIN_VALUE
            var nextWorkoutIndex = 0
            return sampleTimes.map { t ->
                if (previousSampleMs != Long.MIN_VALUE) {
                    accumulated *= 2.0.pow(-(t - previousSampleMs).toDouble() / halfLifeMs)
                }
                while (nextWorkoutIndex < sortedWorkouts.size && sortedWorkouts[nextWorkoutIndex].endTimeMs <= t) {
                    val w = sortedWorkouts[nextWorkoutIndex]
                    accumulated += gain * w.trimp * 2.0.pow(-(t - w.endTimeMs).toDouble() / halfLifeMs)
                    nextWorkoutIndex++
                }
                previousSampleMs = t
                curvePoint(t, rangeStartMs, accumulated.toFloat())
            }
        }

        private fun curvePoint(
            timestampMs: Long,
            rangeStartMs: Long,
            fatigueValue: Float,
        ): FatigueCurvePoint =
            FatigueCurvePoint(
                timestampMs = timestampMs,
                timeMinutesFromStart = ((timestampMs - rangeStartMs) / MILLIS_PER_MINUTE.toDouble()).toFloat(),
                fatigueValue = fatigueValue,
            )

        fun evaluateAt(
            timestampMs: Long,
            config: ResidualFatigueConfig,
            retainedWorkouts: List<FatigueWorkoutInput>,
        ): Float {
            if (config.halfLifeHours <= 0f) return 0f
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
