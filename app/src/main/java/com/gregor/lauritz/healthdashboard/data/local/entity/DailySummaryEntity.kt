package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    @PrimaryKey val dateMidnightMs: Long,
    val sleepScore: Float? = null,
    val loadScore: Float? = null,
    val strainRatio: Float? = null,
    val nocturnalRhr: Float? = null,
    val nocturnalHrv: Float? = null,
    val sleepDurationMinutes: Int? = null,
    val deepSleepPercent: Float? = null,
    val remSleepPercent: Float? = null,
    val totalTrimp: Float? = null,
    val rhrRatio: Float? = null,
    val hrvZScore: Float? = null,
    val hrvBaseline: Float? = null,
)
