package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainSleepStageType
import kotlin.math.max

data class SleepStageBreakdown(
    val deepMinutes: Int,
    val remMinutes: Int,
    val lightMinutes: Int,
    val awakeMinutes: Int,
    val totalSleepMinutes: Int,
    val timeInBedMinutes: Int,
    val durationMinutes: Int,
    val efficiency: Float,
)

object SleepStageAccumulator {
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val ROUNDING_OFFSET_MILLIS = 30_000L

    fun toMinutes(millis: Long): Int =
        ((millis.coerceAtLeast(0L) + ROUNDING_OFFSET_MILLIS) / MILLIS_PER_MINUTE).toInt()

    /**
     * Per-segment minutes such that, for every stage type, the segment minutes sum exactly to
     * `toMinutes(total milliseconds of that type)` -- the same value [accumulate] reports on the
     * session. Each segment gets the delta of the rounded running total instead of its own rounded
     * duration, so no per-segment rounding bias accumulates and stored stage rows agree with the
     * session's deep/REM/light/awake minutes.
     */
    fun allocateSegmentMinutes(session: DomainSleepSessionRecord): List<Int> {
        val runningMillisByType = mutableMapOf<DomainSleepStageType, Long>()
        return session.stages.map { stage ->
            val durationMs =
                (stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()).coerceAtLeast(0L)
            val before = runningMillisByType[stage.stageType] ?: 0L
            val after = before + durationMs
            runningMillisByType[stage.stageType] = after
            toMinutes(after) - toMinutes(before)
        }
    }

    fun accumulate(session: DomainSleepSessionRecord): SleepStageBreakdown {
        var deepMillis = 0L
        var remMillis = 0L
        var lightMillis = 0L
        var awakeMillis = 0L

        for (stage in session.stages) {
            val durationMs =
                (stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()).coerceAtLeast(0L)
            when (stage.stageType) {
                DomainSleepStageType.DEEP -> deepMillis += durationMs
                DomainSleepStageType.REM -> remMillis += durationMs
                DomainSleepStageType.LIGHT -> lightMillis += durationMs
                DomainSleepStageType.AWAKE -> awakeMillis += durationMs
                DomainSleepStageType.UNKNOWN -> Unit
            }
        }

        val deepMinutes = toMinutes(deepMillis)
        val remMinutes = toMinutes(remMillis)
        val lightMinutes = toMinutes(lightMillis)
        val awakeMinutes = toMinutes(awakeMillis)

        val totalSleepMinutes = deepMinutes + remMinutes + lightMinutes
        val timeInBedMillis = session.endTime.toEpochMilli() - session.startTime.toEpochMilli()
        val timeInBedMinutes = toMinutes(timeInBedMillis)

        // Per-type rounding can push the stage sum 1-2 min past the (separately rounded) time in bed.
        val durationMinutes =
            if (session.stages.isEmpty()) timeInBedMinutes else totalSleepMinutes.coerceAtMost(timeInBedMinutes)
        val efficiency =
            (durationMinutes.toFloat() / max(timeInBedMinutes, 1) * 100f).coerceIn(0f, 100f)

        return SleepStageBreakdown(
            deepMinutes = deepMinutes,
            remMinutes = remMinutes,
            lightMinutes = lightMinutes,
            awakeMinutes = awakeMinutes,
            totalSleepMinutes = totalSleepMinutes,
            timeInBedMinutes = timeInBedMinutes,
            durationMinutes = durationMinutes,
            efficiency = efficiency,
        )
    }
}
