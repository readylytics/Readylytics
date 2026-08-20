package app.readylytics.health.core.healthconnect.data.mapper

import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.domain.model.DomainBodyFatRecord

object BodyFatDataMapper {
    fun toEntity(record: DomainBodyFatRecord): BodyFatRecordEntity =
        BodyFatRecordEntity(
            id = "${record.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            bodyFatPercent = record.percentage,
            deviceName = record.deviceName,
        )

    fun toEntities(records: List<DomainBodyFatRecord>): List<BodyFatRecordEntity> = records.map { toEntity(it) }
}
