package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_sleep_averages")
data class DailySleepAverageEntity(
    @PrimaryKey val dateMidnightMs: Long,
    val durationAvg7d: Float,
    val durationAvg30d: Float,
    val scoreAvg7d: Float,
    val scoreAvg30d: Float,
)
