package app.readylytics.health.data.mapper

import androidx.health.connect.client.records.OxygenSaturationRecord
import app.readylytics.health.data.healthconnect.DeviceLabel
import app.readylytics.health.data.local.entity.OxygenSaturationRecordEntity

object OxygenSaturationDataMapper {
    fun toEntity(record: OxygenSaturationRecord): OxygenSaturationRecordEntity =
        OxygenSaturationRecordEntity(
            id = "${record.metadata.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            percentage = record.percentage.value.toFloat(),
            deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
        )

    fun toEntities(records: List<OxygenSaturationRecord>): List<OxygenSaturationRecordEntity> =
        records.map { toEntity(it) }
}
