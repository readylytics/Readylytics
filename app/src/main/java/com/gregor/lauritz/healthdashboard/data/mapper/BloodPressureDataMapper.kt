package com.gregor.lauritz.healthdashboard.data.mapper

import androidx.health.connect.client.records.BloodPressureRecord
import com.gregor.lauritz.healthdashboard.data.healthconnect.DeviceLabel
import com.gregor.lauritz.healthdashboard.data.local.entity.BloodPressureRecordEntity

object BloodPressureDataMapper {
    fun toEntity(record: BloodPressureRecord): BloodPressureRecordEntity =
        BloodPressureRecordEntity(
            id = "${record.metadata.id}_${record.time.toEpochMilli()}",
            timestampMs = record.time.toEpochMilli(),
            systolicMmHg = record.systolic.inMillimetersOfMercury.toInt(),
            diastolicMmHg = record.diastolic.inMillimetersOfMercury.toInt(),
            deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
        )

    fun toEntities(records: List<BloodPressureRecord>): List<BloodPressureRecordEntity> = records.map { toEntity(it) }
}
