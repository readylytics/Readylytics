package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_hrv_averages")
data class DailyHrvAverageEntity(
    @PrimaryKey val dateMidnightMs: Long,
    val avg7d: Float,
    val avg30d: Float,
)
