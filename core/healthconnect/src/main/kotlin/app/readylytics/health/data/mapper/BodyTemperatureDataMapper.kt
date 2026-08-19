package app.readylytics.health.data.mapper

import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.domain.model.DomainBodyTemperatureRecord

object BodyTemperatureDataMapper {
    fun toEntity(record: DomainBodyTemperatureRecord): BodyTemperatureRecordEntity =
        BodyTemperatureRecordEntity(
            id = "${record.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            celsius = record.celsius,
            deviceName = record.deviceName,
        )

    fun toEntities(records: List<DomainBodyTemperatureRecord>): List<BodyTemperatureRecordEntity> =
        records.map { toEntity(it) }
}
