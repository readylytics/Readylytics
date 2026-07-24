package app.readylytics.health.data.healthconnect

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import app.readylytics.health.domain.model.DomainBloodPressureRecord
import app.readylytics.health.domain.model.DomainBodyFatRecord
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHeartRateSample
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainSleepStage
import app.readylytics.health.domain.model.DomainSleepStageType
import app.readylytics.health.domain.model.DomainStepsRecord
import app.readylytics.health.domain.model.DomainWeightRecord

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

fun ExerciseSessionRecord.toDomain(): DomainExerciseSessionRecord =
    DomainExerciseSessionRecord(
        id = metadata.id,
        startTime = startTime,
        endTime = endTime,
        exerciseType = exerciseType.toString(),
        deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
    )

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
