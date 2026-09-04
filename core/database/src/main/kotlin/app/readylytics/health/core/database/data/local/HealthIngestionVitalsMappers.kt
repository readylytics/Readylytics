package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.model.domain.sync.BloodPressureInput
import app.readylytics.health.core.model.domain.sync.BodyFatInput
import app.readylytics.health.core.model.domain.sync.BodyTemperatureInput
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.OxygenSaturationInput
import app.readylytics.health.core.model.domain.sync.Vo2MaxInput
import app.readylytics.health.core.model.domain.sync.WeightInput

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

internal fun Vo2MaxInput.toEntity() =
    Vo2MaxRecordEntity(
        id = id,
        timestampMs = timestampMs,
        vo2Max = vo2Max,
        measurementMethod = measurementMethod,
        deviceName = deviceName ?: "",
    )
