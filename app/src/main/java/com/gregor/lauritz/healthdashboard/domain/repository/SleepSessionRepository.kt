package com.gregor.lauritz.healthdashboard.domain.repository

import kotlinx.coroutines.flow.Flow

data class SleepSessionData(
    val id: String,
    val deviceName: String?,
    val startTime: Long,
    val endTime: Long,
    val durationMinutes: Int,
    val deepSleepMinutes: Int,
    val lightSleepMinutes: Int,
    val remSleepMinutes: Int,
    val awakeMinutes: Int,
)

interface SleepSessionRepository {
    fun observeSince(fromMs: Long): Flow<List<SleepSessionData>>

    suspend fun getSince(fromMs: Long): List<SleepSessionData>

    suspend fun getPaged(
        fromMs: Long,
        limit: Int,
        offset: Int,
    ): List<SleepSessionData>

    suspend fun countSince(fromMs: Long): Int
}
