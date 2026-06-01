package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface DailySummaryDao {
    @Query("SELECT * FROM daily_summaries ORDER BY dateMidnightMs DESC LIMIT 1")
    fun _observeLatest(): Flow<DailySummaryEntity?>

    fun observeLatest(): Flow<DailySummaryEntity?> = _observeLatest().distinctUntilChanged()

    @Query(
        "SELECT * FROM daily_summaries WHERE dateMidnightMs >= :fromMs " +
            "ORDER BY dateMidnightMs DESC",
    )
    fun _observeSince(fromMs: Long): Flow<List<DailySummaryEntity>>

    fun observeSince(fromMs: Long): Flow<List<DailySummaryEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Query("SELECT * FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    fun _observeByDate(dateMidnightMs: Long): Flow<DailySummaryEntity?>

    fun observeByDate(dateMidnightMs: Long): Flow<DailySummaryEntity?> =
        _observeByDate(dateMidnightMs).distinctUntilChanged()

    @Upsert
    suspend fun upsert(summary: DailySummaryEntity)

    @Upsert
    suspend fun upsertAll(summaries: List<DailySummaryEntity>)

    @Query("SELECT * FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getByDate(dateMidnightMs: Long): DailySummaryEntity?

    @Query(
        "SELECT * FROM daily_summaries WHERE dateMidnightMs >= :fromMs ORDER BY dateMidnightMs ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun getPaged(
        fromMs: Long,
        limit: Int,
        offset: Int,
    ): List<DailySummaryEntity>

    @Query("SELECT * FROM daily_summaries WHERE dateMidnightMs >= :fromMs ORDER BY dateMidnightMs ASC")
    suspend fun getSince(fromMs: Long): List<DailySummaryEntity>

    @Query("SELECT * FROM daily_summaries ORDER BY dateMidnightMs ASC")
    suspend fun getAllSummaries(): List<DailySummaryEntity>

    @Query("DELETE FROM daily_summaries WHERE dateMidnightMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("SELECT COUNT(*) FROM daily_summaries")
    suspend fun count(): Int

    @Query("DELETE FROM daily_summaries")
    suspend fun deleteAll(): Int

    @Query("SELECT * FROM daily_summaries WHERE dateMidnightMs IN (:dates)")
    suspend fun getByDates(dates: List<Long>): List<DailySummaryEntity>

    @Query(
        "UPDATE daily_summaries SET hrv_mu_mssd = :hrvMuMssd, " +
            "hrv_sigma_mssd = :hrvSigmaMssd, rhr_bpm = :rhrBpm, " +
            "baseline_calculated_at_date = :baselineCalculatedAtDate, " +
            "baseline_version = :baselineVersion " +
            "WHERE dateMidnightMs = :dateMidnightMs",
    )
    suspend fun updateBaselines(
        dateMidnightMs: Long,
        hrvMuMssd: Float?,
        hrvSigmaMssd: Float?,
        rhrBpm: Float?,
        baselineCalculatedAtDate: java.time.LocalDate?,
        baselineVersion: Int?,
    )
}
