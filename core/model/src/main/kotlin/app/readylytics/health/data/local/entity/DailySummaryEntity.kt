package app.readylytics.health.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.readylytics.health.domain.model.ReadinessResult
import app.readylytics.health.domain.model.Diagnostics
import app.readylytics.health.domain.model.Contributors
import kotlinx.serialization.SerialName
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
    val nocturnalHrv: Int? = null,
    val sleepDurationMinutes: Int? = null,
    val deepSleepPercent: Float? = null,
    val remSleepPercent: Float? = null,
    val hrvBaseline: Int? = null,
    val restingHeartRate: Int? = null,
    val restingHrRatio: Float? = null,
    val stepCount: Int? = null,
    val zLnHrv: Float? = null,
    val zRhr: Float? = null,
    val recoveryFlags: String? = null,
    val hrvSigma: Float? = null,
    @Embedded(prefix = "diag_")
    @SerialName("diagnostics")
    val diagnosticsEmbedded: Diagnostics = Diagnostics(),
    @Embedded(prefix = "contrib_")
    @SerialName("contributors")
    val contributorsEmbedded: Contributors = Contributors(),
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
    @ColumnInfo(name = "rhr_sigma", defaultValue = "NULL")
    val rhrSigma: Float? = null,
    @ColumnInfo(name = "baseline_calculated_at_date")
    @Serializable(with = LocalDateSerializer::class)
    val baselineCalculatedAtDate: LocalDate? = null,
    @ColumnInfo(name = "hr_max")
    val hrMax: Float? = null,
    @ColumnInfo(name = "snapshot_profile")
    val snapshotProfile: String? = null,
    @ColumnInfo(name = "snapshot_calibration_phase")
    val snapshotCalibrationPhase: String? = null,
    @ColumnInfo(name = "hrv_sigma_prior")
    val hrvSigmaPrior: Float? = null,
    @ColumnInfo(name = "ras_scaling_factor")
    val rasScalingFactor: Float? = null,
    @ColumnInfo(name = "baseline_observation_count")
    val baselineObservationCount: Int? = null,
    val trimpWorkoutOnly: Float? = null,
    val trimpEverydayHr: Float? = null,
    val rasWorkoutOnly: Float? = null,
    val rasEverydayHr: Float? = null,
    val totalRasWorkoutOnly: Float? = null,
    val totalRasEverydayHr: Float? = null,
    val atlWorkoutOnly: Float? = null,
    val atlEverydayHr: Float? = null,
    val ctlWorkoutOnly: Float? = null,
    val ctlEverydayHr: Float? = null,
    val strainRatioWorkoutOnly: Float? = null,
    val strainRatioEverydayHr: Float? = null,
    val loadScoreWorkoutOnly: Float? = null,
    val loadScoreEverydayHr: Float? = null,
    val readinessWorkoutOnly: Float? = null,
    val readinessEverydayHr: Float? = null,
    val everydayCoverageMinutes: Int? = null,
    val everydayLoadConfidence: String? = null,
    val supplementalSleepDurationMinutes: Int? = null,
    val napCount: Int? = null,
) {
    val diagnostics: Diagnostics
        get() = diagnosticsEmbedded
    val contributors: Contributors
        get() = contributorsEmbedded
}
