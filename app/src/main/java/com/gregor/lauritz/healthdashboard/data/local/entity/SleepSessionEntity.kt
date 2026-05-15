package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sleep_sessions",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["endTime"]),
    ],
)
data class SleepSessionEntity(
    @PrimaryKey val id: String,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val efficiency: Float,
    val deepSleepMinutes: Int,
    val remSleepMinutes: Int,
    val lightSleepMinutes: Int,
    val awakeMinutes: Int,
    val sleepScore: Float? = null,
    val startZoneOffsetSeconds: Int? = null,
    val endZoneOffsetSeconds: Int? = null,
    val deviceName: String? = null,
)
