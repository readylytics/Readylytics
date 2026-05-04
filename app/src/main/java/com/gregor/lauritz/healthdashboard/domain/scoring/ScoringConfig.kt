package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.domain.scoring.components.AuditTrail
import com.gregor.lauritz.healthdashboard.domain.scoring.components.CircadianConsistencyConfig
import com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds
import com.gregor.lauritz.healthdashboard.domain.scoring.components.RestorationWeights
import com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets

data class ScoringConfig(
    val restoration: RestorationWeights,
    val sleepTargets: SleepArchitectureTargets,
    val emergencyFlags: EmergencyFlagThresholds,
    val circadianConsistency: CircadianConsistencyConfig,
    val paiScalingFactor: Float,
    val auditTrail: AuditTrail,
)
