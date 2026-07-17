package app.readylytics.health.data.healthconnect

import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.local.entity.SleepStageEntity
import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainSleepStageType
import app.readylytics.health.domain.model.SleepStageType
import kotlin.math.max

object SleepDataMapper {
    fun mapSleepSession(session: DomainSleepSessionRecord): SleepSessionEntity {
        var deepMinutes = 0
        var remMinutes = 0
        var lightMinutes = 0
        var awakeMinutes = 0

        for (stage in session.stages) {
            val durationMin = ((stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()) / 60_000L).toInt()
            when (stage.stageType) {
                DomainSleepStageType.DEEP -> deepMinutes += durationMin
                DomainSleepStageType.REM -> remMinutes += durationMin
                DomainSleepStageType.LIGHT -> lightMinutes += durationMin
                DomainSleepStageType.AWAKE -> awakeMinutes += durationMin
                DomainSleepStageType.UNKNOWN -> Unit
            }
        }

        val totalSleepMinutes = deepMinutes + remMinutes + lightMinutes
        val timeInBedMinutes = ((session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60_000L).toInt()

        // HC-006/OD-2: a stage-less HC sleep session (no per-stage breakdown at all) reports zero
        // deep/rem/light minutes, which previously left durationMinutes = 0 -- SleepDaySegment's
        // `durationMinutes > 0` invariant then throws for every day whose lookback window includes
        // it. Fall back to the raw session span so Duration scoring still works; the all-zero
        // deep/rem/light combination (with a positive fallback duration) is the signal
        // ComputeSleepMetricsUseCase uses to reweight Architecture out of the Sleep Score instead of
        // scoring it as 0% (see `stagesSuspicious` there).
        val durationMinutes = if (session.stages.isEmpty()) timeInBedMinutes else totalSleepMinutes
        val efficiency = durationMinutes.toFloat() / max(timeInBedMinutes, 1) * 100f

        return SleepSessionEntity(
            id = session.id,
            startTime = session.startTime.toEpochMilli(),
            endTime = session.endTime.toEpochMilli(),
            durationMinutes = durationMinutes,
            efficiency = efficiency,
            deepSleepMinutes = deepMinutes,
            remSleepMinutes = remMinutes,
            lightSleepMinutes = lightMinutes,
            awakeMinutes = awakeMinutes,
            sleepScore = null,
            startZoneOffsetSeconds = session.startZoneOffsetSeconds,
            endZoneOffsetSeconds = session.endZoneOffsetSeconds,
            deviceName = session.deviceName,
        )
    }

    fun mapSleepSessionStages(session: DomainSleepSessionRecord): List<SleepStageEntity> =
        session.stages.map { stage ->
            val durationMin =
                ((stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()) / 60_000L)
                    .toInt()
            val stageType =
                when (stage.stageType) {
                    DomainSleepStageType.DEEP -> SleepStageType.DEEP.value
                    DomainSleepStageType.REM -> SleepStageType.REM.value
                    DomainSleepStageType.LIGHT -> SleepStageType.LIGHT.value
                    DomainSleepStageType.AWAKE -> SleepStageType.AWAKE.value
                    DomainSleepStageType.UNKNOWN -> SleepStageType.UNKNOWN.value
                }
            SleepStageEntity(
                sessionId = session.id,
                stageType = stageType,
                startTime = stage.startTime.toEpochMilli(),
                endTime = stage.endTime.toEpochMilli(),
                durationMinutes = durationMin,
            )
        }
}
