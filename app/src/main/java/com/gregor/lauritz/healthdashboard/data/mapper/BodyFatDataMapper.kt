package com.gregor.lauritz.healthdashboard.data.mapper

import androidx.health.connect.client.records.BodyFatRecord
import com.gregor.lauritz.healthdashboard.data.local.entity.BodyFatRecordEntity
import com.gregor.lauritz.healthdashboard.data.healthconnect.DeviceLabel

object BodyFatDataMapper {
    fun toEntity(record: BodyFatRecord): BodyFatRecordEntity =
        BodyFatRecordEntity(
            id = "${record.metadata.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            bodyFatPercent = (record.percentage.value * 100f).toFloat(),
            deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
        )

    fun toEntities(records: List<BodyFatRecord>): List<BodyFatRecordEntity> =
        records.map { toEntity(it) }
}
