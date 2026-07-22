package app.readylytics.health.data.mapper

import app.readylytics.health.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.domain.model.BloodPressureRecord
import app.readylytics.health.domain.model.BodyFatRecord
import app.readylytics.health.domain.model.WeightRecord
import java.time.Instant

object WeightRecordMapper {
    fun toDomain(entity: WeightRecordEntity): WeightRecord =
        WeightRecord(
            id = entity.id,
            time = Instant.ofEpochMilli(entity.timestampMs),
            weightKg = entity.weightKg,
            deviceName = entity.deviceName,
        )

    fun toEntity(domain: WeightRecord): WeightRecordEntity =
        WeightRecordEntity(
            id = domain.id,
            timestampMs = domain.time.toEpochMilli(),
            weightKg = domain.weightKg,
            deviceName = domain.deviceName,
        )
}

object BloodPressureRecordMapper {
    fun toDomain(entity: BloodPressureRecordEntity): BloodPressureRecord =
        BloodPressureRecord(
            id = entity.id,
            time = Instant.ofEpochMilli(entity.timestampMs),
            systolicMmHg = entity.systolicMmHg,
            diastolicMmHg = entity.diastolicMmHg,
            deviceName = entity.deviceName,
        )

    fun toEntity(domain: BloodPressureRecord): BloodPressureRecordEntity =
        BloodPressureRecordEntity(
            id = domain.id,
            timestampMs = domain.time.toEpochMilli(),
            systolicMmHg = domain.systolicMmHg,
            diastolicMmHg = domain.diastolicMmHg,
            deviceName = domain.deviceName,
        )
}

object BodyFatRecordMapper {
    fun toDomain(entity: BodyFatRecordEntity): BodyFatRecord =
        BodyFatRecord(
            id = entity.id,
            time = Instant.ofEpochMilli(entity.timestampMs),
            bodyFatPercent = entity.bodyFatPercent,
            deviceName = entity.deviceName,
        )

    fun toEntity(domain: BodyFatRecord): BodyFatRecordEntity =
        BodyFatRecordEntity(
            id = domain.id,
            timestampMs = domain.time.toEpochMilli(),
            bodyFatPercent = domain.bodyFatPercent,
            deviceName = domain.deviceName,
        )
}
