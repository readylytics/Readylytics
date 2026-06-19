package app.readylytics.health.data.mapper

import app.readylytics.health.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.domain.model.DomainOxygenSaturationRecord

object OxygenSaturationDataMapper {
    fun toEntity(record: DomainOxygenSaturationRecord): OxygenSaturationRecordEntity =
        OxygenSaturationRecordEntity(
            id = "${record.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            percentage = record.percentage,
            deviceName = record.deviceName,
        )

    fun toEntities(records: List<DomainOxygenSaturationRecord>): List<OxygenSaturationRecordEntity> =
        records.map { toEntity(it) }
}
