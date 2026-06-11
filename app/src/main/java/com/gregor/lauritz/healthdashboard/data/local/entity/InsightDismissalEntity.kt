package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "insight_dismissals",
    primaryKeys = ["dateMidnightMs", "type"],
)
data class InsightDismissalEntity(
    val dateMidnightMs: Long,
    val type: String,
)
