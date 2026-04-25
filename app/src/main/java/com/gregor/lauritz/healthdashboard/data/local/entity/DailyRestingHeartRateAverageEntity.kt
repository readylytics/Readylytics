package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_rhr_averages")
data class DailyRestingHeartRateAverageEntity(
    @PrimaryKey val dateMidnightMs: Long,
    val avg7d: Float,
    val avg30d: Float,
)
