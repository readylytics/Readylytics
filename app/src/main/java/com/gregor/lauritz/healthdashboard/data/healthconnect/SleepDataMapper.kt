package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.records.SleepSessionRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepStageEntity
import kotlin.math.max

object SleepDataMapper {
    fun mapSleepSession(session: SleepSessionRecord): SleepSessionEntity {
        var deepMinutes = 0
        var remMinutes = 0
        var lightMinutes = 0
        var awakeMinutes = 0

        for (stage in session.stages) {
            val durationMin = ((stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()) / 60_000L).toInt()
            when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> deepMinutes += durationMin
                SleepSessionRecord.STAGE_TYPE_REM -> remMinutes += durationMin
                SleepSessionRecord.STAGE_TYPE_LIGHT,
                SleepSessionRecord.STAGE_TYPE_SLEEPING,
                -> lightMinutes += durationMin
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                -> awakeMinutes += durationMin
            }
        }

        val totalSleepMinutes = deepMinutes + remMinutes + lightMinutes
        val timeInBedMinutes = ((session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60_000L).toInt()
        val efficiency = totalSleepMinutes.toFloat() / max(timeInBedMinutes, 1) * 100f

        return SleepSessionEntity(
            id = session.metadata.id,
            startTime = session.startTime.toEpochMilli(),
            endTime = session.endTime.toEpochMilli(),
            durationMinutes = totalSleepMinutes,
            efficiency = efficiency,
            deepSleepMinutes = deepMinutes,
            remSleepMinutes = remMinutes,
            lightSleepMinutes = lightMinutes,
            awakeMinutes = awakeMinutes,
            sleepScore = null,
            startZoneOffsetSeconds = session.startZoneOffset?.totalSeconds,
            endZoneOffsetSeconds = session.endZoneOffset?.totalSeconds,
            deviceName = DeviceLabel.from(session.metadata.device, session.metadata.dataOrigin),
        )
    }

    fun mapSleepSessionStages(session: SleepSessionRecord): List<SleepStageEntity> =
        session.stages.map { stage ->
            val durationMin =
                ((stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()) / 60_000L)
                    .toInt()
            val stageType =
                when (stage.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
                    SleepSessionRecord.STAGE_TYPE_REM -> "REM"
                    SleepSessionRecord.STAGE_TYPE_LIGHT,
                    SleepSessionRecord.STAGE_TYPE_SLEEPING,
                    -> "LIGHT"
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                    -> "AWAKE"
                    else -> "UNKNOWN"
                }
            SleepStageEntity(
                sessionId = session.metadata.id,
                stageType = stageType,
                startTime = stage.startTime.toEpochMilli(),
                endTime = stage.endTime.toEpochMilli(),
                durationMinutes = durationMin,
            )
        }
}
