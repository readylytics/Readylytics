package app.readylytics.health.data.mapper

import app.readylytics.health.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.domain.model.DomainBloodPressureRecord

object BloodPressureDataMapper {
    fun toEntity(record: DomainBloodPressureRecord): BloodPressureRecordEntity =
        BloodPressureRecordEntity(
            id = "${record.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            systolicMmHg = record.systolicMmHg,
            diastolicMmHg = record.diastolicMmHg,
            deviceName = record.deviceName,
        )

    fun toEntities(records: List<DomainBloodPressureRecord>): List<BloodPressureRecordEntity> =
        records.map { toEntity(it) }
}
