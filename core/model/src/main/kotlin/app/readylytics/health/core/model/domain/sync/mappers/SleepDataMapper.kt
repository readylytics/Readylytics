package app.readylytics.health.core.model.domain.sync.mappers

import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainSleepStageType
import app.readylytics.health.core.model.domain.model.SleepStageType
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SleepStageInput

object SleepDataMapper {
    fun mapSleepSession(session: DomainSleepSessionRecord): SleepSessionInput {
        val breakdown = SleepStageAccumulator.accumulate(session)

        return SleepSessionInput(
            id = session.id,
            startTime = session.startTime.toEpochMilli(),
            endTime = session.endTime.toEpochMilli(),
            durationMinutes = breakdown.durationMinutes,
            efficiency = breakdown.efficiency,
            deepSleepMinutes = breakdown.deepMinutes,
            remSleepMinutes = breakdown.remMinutes,
            lightSleepMinutes = breakdown.lightMinutes,
            awakeMinutes = breakdown.awakeMinutes,
            sleepScore = null,
            startZoneOffsetSeconds = session.startZoneOffsetSeconds,
            endZoneOffsetSeconds = session.endZoneOffsetSeconds,
            deviceName = session.deviceName,
        )
    }

    fun mapSleepSessionStages(session: DomainSleepSessionRecord): List<SleepStageInput> {
        val segmentMinutes = SleepStageAccumulator.allocateSegmentMinutes(session)
        return session.stages.mapIndexed { index, stage ->
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
                durationMinutes = segmentMinutes[index],
            )
        }
    }
}
