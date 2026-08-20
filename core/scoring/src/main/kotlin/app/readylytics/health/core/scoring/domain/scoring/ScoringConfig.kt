package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.ScoringConfig

import app.readylytics.health.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.domain.scoring.WorkoutIntensityLevel

import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.scoring.TrimpModel
import app.readylytics.health.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.LoadCoverageConfidence

import app.readylytics.health.core.scoring.domain.scoring.components.AuditTrail
import app.readylytics.health.core.scoring.domain.scoring.components.CircadianConsistencyConfig
import app.readylytics.health.core.scoring.domain.scoring.components.EmergencyFlagThresholds
import app.readylytics.health.core.scoring.domain.scoring.components.RestorationWeights
import app.readylytics.health.core.scoring.domain.scoring.components.SleepArchitectureTargets

data class ScoringConfig(
    val restoration: RestorationWeights,
    val sleepTargets: SleepArchitectureTargets,
    val emergencyFlags: EmergencyFlagThresholds,
    val circadianConsistency: CircadianConsistencyConfig,
    val rasScalingFactor: Float,
    val auditTrail: AuditTrail,
    val trimpModel: TrimpModel,
    val banisterMultiplier: Float,
    val chengBeta: Float,
    val itrimB: Float,
    val hrvSaturationZ: Float = ScoringConstants.HRV_SCORE_SATURATION_Z,
    val sleepWeightProfile: SleepScoreWeightProfile = SleepScoreWeightProfile.DEFAULT,
    val hypersomniaOnsetRatio: Float = ScoringConstants.Sleep.DEFAULT_HYPERSOMNIA_ONSET_RATIO,
)
