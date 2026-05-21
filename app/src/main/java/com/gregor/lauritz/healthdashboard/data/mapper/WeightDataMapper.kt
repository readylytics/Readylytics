package com.gregor.lauritz.healthdashboard.data.mapper

import androidx.health.connect.client.records.WeightRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.WeightRecordEntity
import com.gregor.lauritz.healthdashboard.data.healthconnect.DeviceLabel

object WeightDataMapper {
    fun toEntity(record: WeightRecord): WeightRecordEntity =
        WeightRecordEntity(
            id = "${record.metadata.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            weightKg = record.weight.inKilograms.toFloat(),
            deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
        )

    fun toEntities(records: List<WeightRecord>): List<WeightRecordEntity> =
        records.map { toEntity(it) }
}
