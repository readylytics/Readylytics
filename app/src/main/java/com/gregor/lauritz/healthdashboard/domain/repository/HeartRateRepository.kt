package com.gregor.lauritz.healthdashboard.domain.repository

import kotlinx.coroutines.flow.Flow

data class HeartRateRecordData(
    val id: String,
    val timestampMs: Long,
    val beatsPerMinute: Int,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)

data class HrvRecordData(
    val id: String,
    val timestampMs: Long,
    val rmssdMs: Float,
    val recordType: String,
    val sessionId: String? = null,
    val deviceName: String? = null,
)

interface HeartRateRepository {
    fun observeSleepHrSince(fromMs: Long): Flow<List<HeartRateRecordData>>

    suspend fun getMinHrInRange(
        startTimeMs: Long,
        endTimeMs: Long,
    ): Int?

    suspend fun getByTimeRange(
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<HeartRateRecordData>

    fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordData>>
}
