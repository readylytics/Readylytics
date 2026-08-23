package app.readylytics.health.core.healthconnect.data.mapper

import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord

object BodyFatDataMapper {
    fun toEntity(record: DomainBodyFatRecord): BodyFatRecordEntity =
        BodyFatRecordEntity(
            id = extractBodyFatRecordId(record),
            timestampMs = extractBodyFatTimestamp(record),
            bodyFatPercent = record.percentage,
            deviceName = extractBodyFatDeviceName(record),
        )

    fun toEntities(records: List<DomainBodyFatRecord>): List<BodyFatRecordEntity> =
        MapperHelpers.mapRecordList(records, ::toEntity)

    private fun extractBodyFatRecordId(record: DomainBodyFatRecord): String =
        MapperHelpers.extractRecordIdFromInstant(record.id, record.time)

    private fun extractBodyFatTimestamp(record: DomainBodyFatRecord): Long =
        MapperHelpers.extractTimestampMs(record.time)

    private fun extractBodyFatDeviceName(record: DomainBodyFatRecord): String =
        MapperHelpers.extractDeviceName(record.deviceName)
}
