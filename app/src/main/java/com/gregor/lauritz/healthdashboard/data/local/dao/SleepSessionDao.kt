package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface SleepSessionDao {
    @Query("SELECT * FROM sleep_sessions WHERE startTime >= :fromMs ORDER BY startTime DESC")
    fun _observeSince(fromMs: Long): Flow<List<SleepSessionEntity>>

    fun observeSince(fromMs: Long): Flow<List<SleepSessionEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Query("SELECT * FROM sleep_sessions ORDER BY startTime DESC LIMIT 1")
    fun _observeLatest(): Flow<SleepSessionEntity?>

    fun observeLatest(): Flow<SleepSessionEntity?> = _observeLatest().distinctUntilChanged()

    @Query("SELECT * FROM sleep_sessions ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatest(): SleepSessionEntity?

    @Upsert
    suspend fun upsertAll(sessions: List<SleepSessionEntity>)

    @Query("DELETE FROM sleep_sessions WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM sleep_sessions WHERE startTime >= :fromMs")
    suspend fun countSince(fromMs: Long): Int

    @Query("SELECT * FROM sleep_sessions WHERE startTime >= :fromMs ORDER BY startTime ASC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(
        fromMs: Long,
        limit: Int,
        offset: Int,
    ): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_sessions WHERE startTime >= :fromMs ORDER BY startTime ASC")
    suspend fun getSince(fromMs: Long): List<SleepSessionEntity>

    @Query("SELECT * FROM sleep_sessions WHERE startTime >= :fromMs AND endTime <= :toMs ORDER BY startTime ASC")
    suspend fun getBetween(
        fromMs: Long,
        toMs: Long,
    ): List<SleepSessionEntity>

    @Query(
        "SELECT * FROM sleep_sessions WHERE endTime >= :fromMs AND startTime <= :toMs ORDER BY startTime ASC, id ASC",
    )
    suspend fun getOverlapping(
        fromMs: Long,
        toMs: Long,
    ): List<SleepSessionEntity>

    @Query(
        "SELECT * FROM sleep_sessions WHERE endTime >= :fromMs AND endTime < :toMs ORDER BY endTime ASC, id ASC LIMIT 1",
    )
    suspend fun getSessionEndingInRange(
        fromMs: Long,
        toMs: Long,
    ): SleepSessionEntity?

    @Query(
        "SELECT * FROM sleep_sessions WHERE endTime >= :fromMs AND endTime < :toMs ORDER BY endTime ASC, id ASC LIMIT 1",
    )
    fun _observeFirstSessionEndingInRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<SleepSessionEntity?>

    fun observeFirstSessionEndingInRange(
        fromMs: Long,
        toMs: Long,
    ): Flow<SleepSessionEntity?> = _observeFirstSessionEndingInRange(fromMs, toMs).distinctUntilChanged()

    @Query("DELETE FROM sleep_sessions WHERE endTime < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM sleep_sessions")
    suspend fun count(): Int

    @Query("DELETE FROM sleep_sessions")
    suspend fun deleteAll(): Int

    @Query("SELECT DISTINCT deviceName FROM sleep_sessions WHERE deviceName IS NOT NULL AND deviceName != ''")
    suspend fun getDistinctDeviceNames(): List<String>

    @Query("SELECT MIN(startTime) FROM sleep_sessions")
    fun observeEarliestSessionTime(): Flow<Long?>
}
