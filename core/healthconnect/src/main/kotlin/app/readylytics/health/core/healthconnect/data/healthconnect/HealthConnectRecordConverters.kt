package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.DomainIntervalTotal
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainRouteLocation
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainSleepStage
import app.readylytics.health.core.model.domain.model.DomainSleepStageType
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import app.readylytics.health.core.model.domain.model.RouteState

fun SleepSessionRecord.toDomain(): DomainSleepSessionRecord =
    DomainSleepSessionRecord(
        id = metadata.id,
        startTime = startTime,
        endTime = endTime,
        startZoneOffsetSeconds = startZoneOffset?.totalSeconds,
        endZoneOffsetSeconds = endZoneOffset?.totalSeconds,
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
        stages =
            stages.map { stage ->
                DomainSleepStage(
                    startTime = stage.startTime,
                    endTime = stage.endTime,
                    stageType =
                        when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP -> DomainSleepStageType.DEEP
                            SleepSessionRecord.STAGE_TYPE_REM -> DomainSleepStageType.REM
                            SleepSessionRecord.STAGE_TYPE_LIGHT,
                            SleepSessionRecord.STAGE_TYPE_SLEEPING,
                            -> DomainSleepStageType.LIGHT
                            SleepSessionRecord.STAGE_TYPE_AWAKE,
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
                            -> DomainSleepStageType.AWAKE
                            else -> DomainSleepStageType.UNKNOWN
                        },
                )
            },
    )

fun HeartRateRecord.toDomain(): DomainHeartRateRecord =
    DomainHeartRateRecord(
        id = metadata.id,
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
        samples =
            samples.map { sample ->
                DomainHeartRateSample(
                    time = sample.time,
                    beatsPerMinute = sample.beatsPerMinute.toInt(),
                )
            },
    )

fun HeartRateVariabilityRmssdRecord.toDomain(): DomainHrvRecord =
    DomainHrvRecord(
        id = metadata.id,
        time = time,
        rmssdMs = heartRateVariabilityMillis.toFloat(),
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
    )

fun ExerciseSessionRecord.toDomain(): DomainExerciseSessionRecord = toDomain(exerciseRouteResult)

/**
 * @param totalDistanceMeters authoritative distance the recording app wrote as a separate
 * `DistanceRecord` over this session's window, resolved by [SessionTotalsResolver]. Null falls the
 * mapper back to integrating the GPS polyline, which reads ~1-3% short.
 * @param elevationGainMeters same, for `ElevationGainedRecord`.
 */
fun ExerciseSessionRecord.toDomain(
    routeResult: ExerciseRouteResult?,
    totalDistanceMeters: Double? = null,
    elevationGainMeters: Double? = null,
): DomainExerciseSessionRecord {
    val durationSeconds = (endTime.toEpochMilli() - startTime.toEpochMilli()) / 1000.0
    return DomainExerciseSessionRecord(
        id = metadata.id,
        startTime = startTime,
        endTime = endTime,
        exerciseType = exerciseType.toString(),
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
        routePoints = routeResult.toDomainRoutePoints(),
        totalDistanceMeters = totalDistanceMeters,
        // Derived from the same distance rather than read separately, so the displayed pace/speed
        // can never disagree with the displayed distance.
        avgSpeedMps =
            totalDistanceMeters
                ?.takeIf { durationSeconds > 0.0 }
                ?.let { it / durationSeconds },
        elevationGainMeters = elevationGainMeters,
        routeState = routeResult.toRouteState(),
    )
}

/** Health Connect interval records carrying one cumulative quantity, for session attribution. */
internal fun DistanceRecord.toIntervalTotal(): DomainIntervalTotal =
    DomainIntervalTotal(
        startTime = startTime,
        endTime = endTime,
        value = distance.inMeters,
        originPackage = metadata.dataOrigin.packageName,
    )

internal fun ElevationGainedRecord.toIntervalTotal(): DomainIntervalTotal =
    DomainIntervalTotal(
        startTime = startTime,
        endTime = endTime,
        value = elevation.inMeters,
        originPackage = metadata.dataOrigin.packageName,
    )

/**
 * Converts the route handed back by `ExerciseRouteRequestContract` (per-session consent dialog).
 * Public because the launcher lives in the app module -- feature modules must never see Health
 * Connect types, so the app module converts to domain before passing the points inwards.
 */
fun ExerciseRoute.toDomainRoutePoints(): List<DomainRouteLocation> =
    ExerciseRouteResult.Data(this).toDomainRoutePoints()

internal fun ExerciseRouteResult?.toDomainRoutePoints(): List<DomainRouteLocation> {
    val data = this as? ExerciseRouteResult.Data ?: return emptyList()
    return data.exerciseRoute.route.map { location ->
        DomainRouteLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = location.altitude?.inMeters,
            time = location.time,
            horizontalAccuracyMeters = location.horizontalAccuracy?.inMeters?.toFloat(),
            verticalAccuracyMeters = location.verticalAccuracy?.inMeters?.toFloat(),
        )
    }
}

internal fun ExerciseRouteResult?.toRouteState(): String =
    when (this) {
        is ExerciseRouteResult.Data -> RouteState.IMPORTED
        is ExerciseRouteResult.ConsentRequired -> RouteState.PERMISSION_REQUIRED
        else -> RouteState.NOT_AVAILABLE
    }

fun StepsRecord.toDomain(): DomainStepsRecord =
    DomainStepsRecord(
        id = metadata.id,
        startTime = startTime,
        endTime = endTime,
        count = count,
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
    )

fun WeightRecord.toDomain(): DomainWeightRecord =
    DomainWeightRecord(
        id = metadata.id,
        time = time,
        weightKg = weight.inKilograms.toFloat(),
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
    )

fun BodyFatRecord.toDomain(): DomainBodyFatRecord =
    DomainBodyFatRecord(
        id = metadata.id,
        time = time,
        percentage = percentage.value.toFloat(),
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
    )

fun BloodPressureRecord.toDomain(): DomainBloodPressureRecord =
    DomainBloodPressureRecord(
        id = metadata.id,
        time = time,
        systolicMmHg = systolic.inMillimetersOfMercury.toInt(),
        diastolicMmHg = diastolic.inMillimetersOfMercury.toInt(),
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
    )

fun OxygenSaturationRecord.toDomain(): DomainOxygenSaturationRecord =
    DomainOxygenSaturationRecord(
        id = metadata.id,
        time = time,
        percentage = percentage.value.toFloat(),
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
    )

fun BodyTemperatureRecord.toDomain(): DomainBodyTemperatureRecord =
    DomainBodyTemperatureRecord(
        id = metadata.id,
        time = time,
        celsius = temperature.inCelsius.toFloat(),
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
    )
