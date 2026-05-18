package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "heart_rate_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["recordType", "timestampMs"]),
        Index(value = ["sessionId", "recordType", "beatsPerMinute"]),
    ],
)
data class HeartRateRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)
