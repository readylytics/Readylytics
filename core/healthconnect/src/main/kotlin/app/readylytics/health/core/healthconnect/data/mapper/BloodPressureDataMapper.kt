package app.readylytics.health.core.healthconnect.data.mapper

import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord

object BloodPressureDataMapper {
    fun toEntity(record: DomainBloodPressureRecord): BloodPressureRecordEntity =
        BloodPressureRecordEntity(
            id = extractBloodPressureRecordId(record),
            timestampMs = extractBloodPressureTimestamp(record),
            systolicMmHg = record.systolicMmHg,
            diastolicMmHg = record.diastolicMmHg,
            deviceName = extractBloodPressureDeviceName(record),
        )

    fun toEntities(records: List<DomainBloodPressureRecord>): List<BloodPressureRecordEntity> =
        MapperHelpers.mapRecordList(records, ::toEntity)

    private fun extractBloodPressureRecordId(record: DomainBloodPressureRecord): String =
        MapperHelpers.extractRecordIdFromInstant(record.id, record.time)

    private fun extractBloodPressureTimestamp(record: DomainBloodPressureRecord): Long =
        MapperHelpers.extractTimestampMs(record.time)

    private fun extractBloodPressureDeviceName(record: DomainBloodPressureRecord): String? =
        MapperHelpers.extractDeviceName(record.deviceName)
}
