package app.readylytics.health.core.healthconnect.data.mapper

import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord

object OxygenSaturationDataMapper {
    fun toEntity(record: DomainOxygenSaturationRecord): OxygenSaturationRecordEntity =
        OxygenSaturationRecordEntity(
            id = extractOxygenSaturationRecordId(record),
            timestampMs = extractOxygenSaturationTimestamp(record),
            percentage = record.percentage,
            deviceName = extractOxygenSaturationDeviceName(record),
        )

    fun toEntities(records: List<DomainOxygenSaturationRecord>): List<OxygenSaturationRecordEntity> =
        MapperHelpers.mapRecordList(records, ::toEntity)

    private fun extractOxygenSaturationRecordId(record: DomainOxygenSaturationRecord): String =
        MapperHelpers.extractRecordIdFromInstant(record.id, record.time)

    private fun extractOxygenSaturationTimestamp(record: DomainOxygenSaturationRecord): Long =
        MapperHelpers.extractTimestampMs(record.time)

    private fun extractOxygenSaturationDeviceName(record: DomainOxygenSaturationRecord): String =
        MapperHelpers.extractDeviceName(record.deviceName)
}
