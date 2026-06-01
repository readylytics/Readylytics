package com.gregor.lauritz.healthdashboard.data.mapper

import androidx.health.connect.client.records.OxygenSaturationRecord
import com.gregor.lauritz.healthdashboard.data.healthconnect.DeviceLabel
import com.gregor.lauritz.healthdashboard.data.local.entity.OxygenSaturationRecordEntity

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
