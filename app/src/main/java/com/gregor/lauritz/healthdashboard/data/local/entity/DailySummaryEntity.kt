package com.gregor.lauritz.healthdashboard.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessResult
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
@Entity(
    tableName = "daily_summaries",
    indices = [Index(value = ["dateMidnightMs"])],
)
data class DailySummaryEntity(
    @PrimaryKey val dateMidnightMs: Long,
    val sleepScore: Float? = null,
    val loadScore: Float? = null,
    val readinessScore: Float? = null,
    val strainRatio: Float? = null,
    val nocturnalRhr: Int? = null,
    val nocturnalHrv: Int? = null,
    val sleepDurationMinutes: Int? = null,
    val deepSleepPercent: Float? = null,
    val remSleepPercent: Float? = null,
    val totalTrimp: Float? = null,
    val rhrRatio: Float? = null,
    val hrvBaseline: Int? = null,
    val restingHeartRate: Int? = null,
    val restingHrRatio: Float? = null,
    val restingHrBaseline: Int? = null,
    val paiScore: Float? = null,
    val totalPai: Float? = null,
    val stepCount: Int? = null,
    val zLnHrv: Float? = null,
    val zRhr: Float? = null,
    val recoveryFlags: String? = null,
    val hrvSigma: Float? = null,
    @Embedded(prefix = "diag_")
    val diagnostics: ReadinessResult.Diagnostics = ReadinessResult.Diagnostics(),
    @Embedded(prefix = "contrib_")
    val contributors: ReadinessResult.Contributors = ReadinessResult.Contributors(),
    // Legacy/supporting fields not bundled into ReadinessResult
    val rollingMu: Float? = null,
    val rhrDeltaBpm: Float? = null,
    val lateNadir: Boolean? = null,
    val stagesSuspicious: Boolean? = null,
    val isCalibrating: Boolean? = null,
    val hrvScoreContribution: Float? = null,
    val rhrScoreContribution: Float? = null,
    val durationScoreContribution: Float? = null,
    val architectureScoreContribution: Float? = null,
    val loadContribution: Float? = null,
    val sRest: Float? = null,
    val weightKg: Float? = null,
    val bodyFatPercent: Float? = null,
    val bloodPressureSystolic: Int? = null,
    val bloodPressureDiastolic: Int? = null,
    val avgSleepingSpo2: Float? = null,
    // Point-in-time baseline snapshots (Task B)
    @ColumnInfo(name = "hrv_mu_mssd")
    val hrvMuMssd: Float? = null,
    @ColumnInfo(name = "hrv_sigma_mssd")
    val hrvSigmaMssd: Float? = null,
    @ColumnInfo(name = "rhr_bpm")
    val rhrBpm: Float? = null,
    @ColumnInfo(name = "baseline_calculated_at_date")
    @Contextual
    val baselineCalculatedAtDate: LocalDate? = null,
    @ColumnInfo(name = "baseline_version")
    val baselineVersion: Int? = 1,
)
