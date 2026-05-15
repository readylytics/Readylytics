package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "hrv_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["recordType", "timestampMs"]),
        Index(value = ["sessionId"]),
    ],
)
data class HrvRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)
