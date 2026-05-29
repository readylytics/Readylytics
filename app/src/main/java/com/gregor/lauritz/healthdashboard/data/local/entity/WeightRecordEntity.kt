package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "weight_records",
    indices = [
        Index(value = ["timestampMs"]),
        Index(value = ["timestampMs", "deviceName"]),
    ],
)
data class WeightRecordEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val weightKg: Float,
    val deviceName: String? = null,
)
