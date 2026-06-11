package app.readylytics.health.data.mapper

import androidx.health.connect.client.records.WeightRecord
import app.readylytics.health.data.healthconnect.DeviceLabel
import app.readylytics.health.data.local.entity.WeightRecordEntity

object WeightDataMapper {
    fun toEntity(record: WeightRecord): WeightRecordEntity =
        WeightRecordEntity(
            id = "${record.metadata.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            weightKg = record.weight.inKilograms.toFloat(),
            deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
        )

    fun toEntities(records: List<WeightRecord>): List<WeightRecordEntity> = records.map { toEntity(it) }
}
