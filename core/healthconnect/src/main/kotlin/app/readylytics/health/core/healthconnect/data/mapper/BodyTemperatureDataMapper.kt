package app.readylytics.health.core.healthconnect.data.mapper

import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord

object BodyTemperatureDataMapper {
    fun toEntity(record: DomainBodyTemperatureRecord): BodyTemperatureRecordEntity =
        BodyTemperatureRecordEntity(
            id = extractBodyTemperatureRecordId(record),
            timestampMs = extractBodyTemperatureTimestamp(record),
            celsius = record.celsius,
            deviceName = extractBodyTemperatureDeviceName(record),
        )

    fun toEntities(records: List<DomainBodyTemperatureRecord>): List<BodyTemperatureRecordEntity> =
        MapperHelpers.mapRecordList(records, ::toEntity)

    private fun extractBodyTemperatureRecordId(record: DomainBodyTemperatureRecord): String =
        MapperHelpers.extractRecordIdFromInstant(record.id, record.time)

    private fun extractBodyTemperatureTimestamp(record: DomainBodyTemperatureRecord): Long =
        MapperHelpers.extractTimestampMs(record.time)

    private fun extractBodyTemperatureDeviceName(record: DomainBodyTemperatureRecord): String? =
        MapperHelpers.extractDeviceName(record.deviceName)
}
