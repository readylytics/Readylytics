package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.BodyFatInput
import app.readylytics.health.core.model.domain.sync.BodyTemperatureInput
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.OxygenSaturationInput
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.SleepStageInput
import app.readylytics.health.core.model.domain.sync.StepRecordInput
import app.readylytics.health.core.model.domain.sync.WeightInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput

internal fun SleepSessionInput.toEntity() =
    SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        efficiency = efficiency,
        deepSleepMinutes = deepSleepMinutes,
        remSleepMinutes = remSleepMinutes,
        lightSleepMinutes = lightSleepMinutes,
        awakeMinutes = awakeMinutes,
        sleepScore = sleepScore,
        startZoneOffsetSeconds = startZoneOffsetSeconds,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
        deviceName = deviceName,
    )

internal fun SleepStageInput.toEntity() =
    SleepStageEntity(
        sessionId = sessionId,
        stageType = stageType,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
    )

internal fun HeartRateInput.toEntity(sourceRefByBaseId: Map<String, Long>) =
    HeartRateRecordEntity(
        sourceRecordRef = sourceRefByBaseId.getValue(id.substringBefore('_')),
        timestampMs = timestampMs,
        beatsPerMinute = beatsPerMinute,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )

internal fun HrvInput.toEntity(sourceRefByBaseId: Map<String, Long>) =
    HrvRecordEntity(
        sourceRecordRef = sourceRefByBaseId.getValue(id.substringBefore('_')),
        timestampMs = timestampMs,
        rmssdMs = rmssdMs,
        recordType = recordType,
        sessionId = sessionId,
        deviceName = deviceName,
    )

internal fun WorkoutInput.toEntity() =
    WorkoutRecordEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        exerciseType = exerciseType,
        durationMinutes = durationMinutes,
        zone1Minutes = zone1Minutes,
        zone2Minutes = zone2Minutes,
        zone3Minutes = zone3Minutes,
        zone4Minutes = zone4Minutes,
        zone5Minutes = zone5Minutes,
        trimp = trimp,
        avgHr = avgHr,
        deviceName = deviceName,
        totalDistanceMeters = totalDistanceMeters,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainMeters = elevationGainMeters,
        routeState = routeState,
    )

internal fun WeightInput.toEntity() =
    WeightRecordEntity(
        id = id,
        timestampMs = timestampMs,
        weightKg = weightKg,
        deviceName = deviceName,
    )

internal fun BodyFatInput.toEntity() =
    BodyFatRecordEntity(
        id = id,
        timestampMs = timestampMs,
        bodyFatPercent = bodyFatPercent,
        deviceName = deviceName,
    )

internal fun BloodPressureInput.toEntity() =
    BloodPressureRecordEntity(
        id = id,
        timestampMs = timestampMs,
        systolicMmHg = systolicMmHg,
        diastolicMmHg = diastolicMmHg,
        deviceName = deviceName,
    )

internal fun OxygenSaturationInput.toEntity() =
    OxygenSaturationRecordEntity(
        id = id,
        timestampMs = timestampMs,
        percentage = percentage,
        deviceName = deviceName,
    )

internal fun BodyTemperatureInput.toEntity() =
    BodyTemperatureRecordEntity(
        id = id,
        timestampMs = timestampMs,
        celsius = celsius,
        deviceName = deviceName,
    )

internal fun StepRecordInput.toEntity() =
    StepRecordEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        count = count,
        deviceName = deviceName,
    )

internal fun WorkoutRoutePoint.toEntity() =
    WorkoutRoutePointEntity(
        id = id,
        workoutId = workoutId,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        timestampMs = timestampMs,
        horizontalAccuracy = horizontalAccuracy,
        verticalAccuracy = verticalAccuracy,
    )
