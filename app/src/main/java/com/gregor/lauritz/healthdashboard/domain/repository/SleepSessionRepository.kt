package com.gregor.lauritz.healthdashboard.domain.repository

import kotlinx.coroutines.flow.Flow

data class SleepSessionData(
    val id: Long,
    val deviceName: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val duration: Long,
    val deepSleepMs: Long,
    val lightSleepMs: Long,
    val remSleepMs: Long,
    val awakeSleepMs: Long,
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
