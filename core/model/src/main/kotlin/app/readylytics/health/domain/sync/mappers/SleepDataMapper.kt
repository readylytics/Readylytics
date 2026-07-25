package app.readylytics.health.domain.sync.mappers

import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainSleepStageType
import app.readylytics.health.domain.model.SleepStageType
import app.readylytics.health.domain.sync.SleepSessionInput
import app.readylytics.health.domain.sync.SleepStageInput
import kotlin.math.max

object SleepDataMapper {
    fun mapSleepSession(session: DomainSleepSessionRecord): SleepSessionInput {
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

        val durationMinutes = if (session.stages.isEmpty()) timeInBedMinutes else totalSleepMinutes
        val efficiency = durationMinutes.toFloat() / max(timeInBedMinutes, 1) * 100f

        return SleepSessionInput(
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

    fun mapSleepSessionStages(session: DomainSleepSessionRecord): List<SleepStageInput> =
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
            SleepStageInput(
                sessionId = session.id,
                stageType = stageType,
                startTime = stage.startTime.toEpochMilli(),
                endTime = stage.endTime.toEpochMilli(),
                durationMinutes = durationMin,
            )
        }
}
