package app.readylytics.health.data.mapper

import androidx.health.connect.client.records.BodyFatRecord
import app.readylytics.health.data.healthconnect.DeviceLabel
import app.readylytics.health.data.local.entity.BodyFatRecordEntity

object BodyFatDataMapper {
    fun toEntity(record: BodyFatRecord): BodyFatRecordEntity =
        BodyFatRecordEntity(
            id = "${record.metadata.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            bodyFatPercent = record.percentage.value.toFloat(),
            deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
        )

    fun toEntities(records: List<BodyFatRecord>): List<BodyFatRecordEntity> = records.map { toEntity(it) }
}
