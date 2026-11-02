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
        ((millis + ROUNDING_OFFSET_MILLIS) / MILLIS_PER_MINUTE).toInt()

    fun accumulate(session: DomainSleepSessionRecord): SleepStageBreakdown {
        var deepMillis = 0L
        var remMillis = 0L
        var lightMillis = 0L
        var awakeMillis = 0L

        for (stage in session.stages) {
            val durationMs = stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()
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

        val durationMinutes = if (session.stages.isEmpty()) timeInBedMinutes else totalSleepMinutes
        val efficiency = durationMinutes.toFloat() / max(timeInBedMinutes, 1) * 100f

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
