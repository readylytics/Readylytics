package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.sync.SleepSessionInput
import app.readylytics.health.core.model.domain.sync.WorkoutInput

internal fun SleepSessionEntity.toInput() =
    SleepSessionInput(
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

internal fun WorkoutRecordEntity.toInput() =
    WorkoutInput(
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
