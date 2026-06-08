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
            "hr_max = :hrMax, snapshot_profile = :snapshotProfile, " +
            "hrv_sigma_prior = :hrvSigmaPrior, pai_scaling_factor = :paiScalingFactor, " +
            "baseline_observation_count = :baselineObservationCount " +
            "WHERE dateMidnightMs = :dateMidnightMs",
    )
    suspend fun updateBaselines(
        dateMidnightMs: Long,
        hrvMuMssd: Float?,
        hrvSigmaMssd: Float?,
        rhrBpm: Float?,
        baselineCalculatedAtDate: java.time.LocalDate?,
        hrMax: Float? = null,
        snapshotProfile: String? = null,
        hrvSigmaPrior: Float? = null,
        paiScalingFactor: Float? = null,
        baselineObservationCount: Int? = null,
    )

    // Clears the freeze flag so BaselineComputer will recompute on next sync.
    @Query(
        "UPDATE daily_summaries SET " +
            "baseline_calculated_at_date = NULL " +
            "WHERE baseline_calculated_at_date IS NOT NULL",
    )
    suspend fun clearFrozenBaselines()

    @Query(
        "UPDATE daily_summaries SET " +
            "hrv_mu_mssd = NULL, " +
            "hrv_sigma_mssd = NULL, " +
            "rhr_bpm = NULL, " +
            "baseline_calculated_at_date = NULL, " +
            "hr_max = NULL, " +
            "snapshot_profile = NULL, " +
            "snapshot_calibration_phase = NULL, " +
            "hrv_sigma_prior = NULL, " +
            "pai_scaling_factor = NULL, " +
            "baseline_observation_count = NULL",
    )
    suspend fun wipeDerivedBaselines()

    @Query("SELECT MIN(dateMidnightMs) FROM daily_summaries")
    suspend fun getEarliestDateMs(): Long?

    @Query("SELECT MIN(dateMidnightMs) FROM daily_summaries")
    fun _observeEarliestDateMs(): Flow<Long?>

    fun observeEarliestDateMs(): Flow<Long?> = _observeEarliestDateMs().distinctUntilChanged()

    @Query("SELECT rhr_bpm FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getPreciseRhrBaseline(dateMidnightMs: Long): Double?

    @Query("SELECT CAST(ROUND(rhr_bpm) AS INTEGER) FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getRoundedRhrBaseline(dateMidnightMs: Long): Int?

    @Query("SELECT hrv_mu_mssd FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getPreciseHrvMu(dateMidnightMs: Long): Double?

    @Query("SELECT hrvBaseline FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getRoundedHrvBaseline(dateMidnightMs: Long): Int?

    @Query("SELECT hr_max FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getPreciseHrMax(dateMidnightMs: Long): Double?

    @Query("SELECT CAST(ROUND(hr_max) AS INTEGER) FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getRoundedHrMax(dateMidnightMs: Long): Int?

    @Query("SELECT totalPai FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getPrecisePai(dateMidnightMs: Long): Double?

    @Query("SELECT CAST(ROUND(totalPai) AS INTEGER) FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getRoundedPai(dateMidnightMs: Long): Int?

    @Query("SELECT strainRatio FROM daily_summaries WHERE dateMidnightMs = :dateMidnightMs")
    suspend fun getPreciseStrainRatio(dateMidnightMs: Long): Double?
}
