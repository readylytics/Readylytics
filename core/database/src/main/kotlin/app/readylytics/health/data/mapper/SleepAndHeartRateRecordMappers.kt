package app.readylytics.health.data.mapper

import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.domain.model.HeartRateRecord
import app.readylytics.health.domain.model.SleepSession

object HeartRateRecordMapper {
    fun toDomain(entity: HeartRateRecordEntity): HeartRateRecord =
        HeartRateRecord(
            id = entity.id,
            timestampMs = entity.timestampMs,
            beatsPerMinute = entity.beatsPerMinute,
            recordType = entity.recordType,
            sessionId = entity.sessionId,
            deviceName = entity.deviceName,
        )

    fun toEntity(domain: HeartRateRecord): HeartRateRecordEntity =
        HeartRateRecordEntity(
            sourceRecordId = domain.id,
            timestampMs = domain.timestampMs,
            beatsPerMinute = domain.beatsPerMinute,
            recordType = domain.recordType,
            sessionId = domain.sessionId,
            deviceName = domain.deviceName,
        )
}

object SleepSessionMapper {
    fun toDomain(entity: SleepSessionEntity): SleepSession =
        SleepSession(
            id = entity.id,
            startTime = entity.startTime,
            endTime = entity.endTime,
            durationMinutes = entity.durationMinutes,
            efficiency = entity.efficiency,
            deepSleepMinutes = entity.deepSleepMinutes,
            remSleepMinutes = entity.remSleepMinutes,
            lightSleepMinutes = entity.lightSleepMinutes,
            awakeMinutes = entity.awakeMinutes,
            sleepScore = entity.sleepScore,
            startZoneOffsetSeconds = entity.startZoneOffsetSeconds,
            endZoneOffsetSeconds = entity.endZoneOffsetSeconds,
            deviceName = entity.deviceName,
        )

    fun toEntity(domain: SleepSession): SleepSessionEntity =
        SleepSessionEntity(
            id = domain.id,
            startTime = domain.startTime,
            endTime = domain.endTime,
            durationMinutes = domain.durationMinutes,
            efficiency = domain.efficiency,
            deepSleepMinutes = domain.deepSleepMinutes,
            remSleepMinutes = domain.remSleepMinutes,
            lightSleepMinutes = domain.lightSleepMinutes,
            awakeMinutes = domain.awakeMinutes,
            sleepScore = domain.sleepScore,
            startZoneOffsetSeconds = domain.startZoneOffsetSeconds,
            endZoneOffsetSeconds = domain.endZoneOffsetSeconds,
            deviceName = domain.deviceName,
        )
}
