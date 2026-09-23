package app.readylytics.health.core.model.domain.sync

import kotlinx.serialization.Serializable

@Serializable
data class ScoringSnapshotPart1(
    val goalSleepHours: Float,
    val hrvBaselineOverride: Float?,
    val rhrBaselineOverride: Float?,
    val maxHeartRate: Int,
    val autoCalculateMaxHr: Boolean,
    val manualZoneEditing: Boolean,
    val zone1MinPercent: Float,
)

@Serializable
data class ScoringSnapshotPart2(
    val zone1MaxPercent: Float,
    val zone2MaxPercent: Float,
    val zone3MaxPercent: Float,
    val zone4MaxPercent: Float,
    val zone1MinBpm: Int,
    val zone1MaxBpm: Int,
    val zone2MaxBpm: Int,
)

@Serializable
data class ScoringSnapshotPart3(
    val zone3MaxBpm: Int,
    val zone4MaxBpm: Int,
    val age: Int,
    val birthDate: String?,
    val gender: String?,
    val heightCm: Float?,
    val hrvOptimalThreshold: Float,
)

@Serializable
data class ScoringSnapshotPart4(
    val hrvWarningThreshold: Float,
    val rhrOptimalThreshold: Float,
    val rhrWarningThreshold: Float,
    val restingHrPercentile: Int,
    val consistencyThresholdMinutes: Int,
    val consistencyEvaluationDays: Int,
    val consistencyBaselineDays: Int,
)

@Serializable
data class ScoringSnapshotPart5(
    val hrrToleranceSeconds: Int,
    val rasScalingFactor: Float,
    val stepGoal: Int,
    val physiologyProfile: String,
    val installDate: Long,
    val circadianThresholdOverride: String?,
    val trimpModel: String,
)

@Serializable
data class ScoringSnapshotPart6(
    val banisterMultiplier: Float,
    val chengBeta: Float,
    val itrimB: Float,
    val scoringZoneId: String,
    val strainLoadSourceMode: String,
    val rasSourceMode: String,
    val coreMergeGapMinutes: Int,
)

@Serializable
data class ScoringSnapshotPart7(
    val supplementalCutoffMinutesOfDay: Int,
    val minimumCountedSleepSegmentMinutes: Int,
    val supplementalArchitectureCoveragePercent: Int,
    val bodyTempElevatedThresholdCelsius: Float,
    val sleepScoreWeightProfile: String,
    val hypersomniaOnsetPercent: Int,
    val residualFatigueHalfLifeHours: Float,
)

@Serializable
data class ScoringSnapshotPart8(
    val residualFatigueGain: Float,
    val trainingReadinessResidualFatigueScale: Float,
    val trainingReadinessLoadBalanceWeight: Float,
    val lastAppliedTrainingReadinessResidualFatigueScale: Float?,
    val lastAppliedTrainingReadinessLoadBalanceWeight: Float?,
    val vo2MaxSourceMode: String,
    val vo2MaxEstimationMethod: String,
)

@Serializable
data class ScoringSnapshotPart9(
    val retentionDaysEnabled: Boolean,
    val retentionDays: Int,
)
