package app.readylytics.health.data.mapper

import app.readylytics.health.data.local.entity.WeightRecordEntity
import app.readylytics.health.domain.model.DomainWeightRecord

object WeightDataMapper {
    fun toEntity(record: DomainWeightRecord): WeightRecordEntity =
        WeightRecordEntity(
            id = "${record.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            weightKg = record.weightKg,
            deviceName = record.deviceName,
        )

    fun toEntities(records: List<DomainWeightRecord>): List<WeightRecordEntity> = records.map { toEntity(it) }
}
