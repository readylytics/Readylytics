package com.gregor.lauritz.healthdashboard.domain.model

data class SleepSessionSummary(
    val efficiency: Float?,
    val startTime: Long,
    val endTime: Long,
)
