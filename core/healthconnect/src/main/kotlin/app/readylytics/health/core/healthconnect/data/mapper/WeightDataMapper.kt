package app.readylytics.health.core.healthconnect.data.mapper

import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import app.readylytics.health.core.model.domain.model.DomainWeightRecord

object WeightDataMapper {
    fun toEntity(record: DomainWeightRecord): WeightRecordEntity =
        WeightRecordEntity(
            id = extractWeightRecordId(record),
            timestampMs = extractWeightTimestamp(record),
            weightKg = record.weightKg,
            deviceName = extractWeightDeviceName(record),
        )

    fun toEntities(records: List<DomainWeightRecord>): List<WeightRecordEntity> =
        MapperHelpers.mapRecordList(records, ::toEntity)

    private fun extractWeightRecordId(record: DomainWeightRecord): String =
        MapperHelpers.extractRecordIdFromInstant(record.id, record.time)

    private fun extractWeightTimestamp(record: DomainWeightRecord): Long =
        MapperHelpers.extractTimestampMs(record.time)

    private fun extractWeightDeviceName(record: DomainWeightRecord): String =
        MapperHelpers.extractDeviceName(record.deviceName)
}
