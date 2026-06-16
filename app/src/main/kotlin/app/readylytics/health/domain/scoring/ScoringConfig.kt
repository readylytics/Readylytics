package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.scoring.components.AuditTrail
import app.readylytics.health.domain.scoring.components.CircadianConsistencyConfig
import app.readylytics.health.domain.scoring.components.EmergencyFlagThresholds
import app.readylytics.health.domain.scoring.components.RestorationWeights
import app.readylytics.health.domain.scoring.components.SleepArchitectureTargets

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
)
