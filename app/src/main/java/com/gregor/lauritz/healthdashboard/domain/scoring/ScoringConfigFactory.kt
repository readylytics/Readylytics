package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.circadian.CircadianStrategyFactory
import com.gregor.lauritz.healthdashboard.domain.scoring.components.AuditTrail
import com.gregor.lauritz.healthdashboard.domain.scoring.components.AuditTrailFactory
import com.gregor.lauritz.healthdashboard.domain.scoring.components.CircadianConsistencyConfig
import com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds
import com.gregor.lauritz.healthdashboard.domain.scoring.components.Phase
import com.gregor.lauritz.healthdashboard.domain.scoring.components.PhaseCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.components.RestorationWeights
import com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargetFactory
import com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoringConfigFactory @Inject constructor() {
    fun build(
        userPreferences: UserPreferences,
        installDate: LocalDate,
        currentDate: LocalDate,
        circadianOverride: Int? = null,
    ): ScoringConfig {
        val daysSinceInstall = ChronoUnit.DAYS.between(installDate, currentDate).toInt().coerceAtLeast(0)

        val restoration = createRestorationWeights(userPreferences.physiologyProfile)
        val sleepTargets = SleepArchitectureTargetFactory.create(userPreferences.age, userPreferences.gender)
        val emergencyFlags = createEmergencyFlagThresholds(userPreferences.physiologyProfile)
        val circadianConsistency = createCircadianConsistencyConfig(
            userPreferences.physiologyProfile,
            circadianOverride,
            userPreferences.consistencyEvaluationDays,
            userPreferences.consistencyBaselineDays,
        )

        val auditTrail = createAuditTrail(daysSinceInstall, currentDate)

        // Hash only configuration parameters (excluding audit metadata) to ensure stable identifier
        val paramsHash = listOf(restoration, sleepTargets, emergencyFlags, circadianConsistency).hashCode()

        val config = ScoringConfig(
            restoration = restoration,
            sleepTargets = sleepTargets,
            emergencyFlags = emergencyFlags,
            circadianConsistency = circadianConsistency,
            auditTrail = auditTrail.copy(configHashCode = paramsHash),
        )

        return config
    }

    private fun createRestorationWeights(profile: PhysiologyProfile): RestorationWeights {
        return when (profile) {
            PhysiologyProfile.ATHLETE -> RestorationWeights(hrvWeight = 0.70f, rhrWeight = 0.30f)
            PhysiologyProfile.ACTIVE -> RestorationWeights(hrvWeight = 0.60f, rhrWeight = 0.40f)
            PhysiologyProfile.GENERAL -> RestorationWeights(hrvWeight = 0.50f, rhrWeight = 0.50f)
            PhysiologyProfile.SEDENTARY -> RestorationWeights(hrvWeight = 0.50f, rhrWeight = 0.50f)
            PhysiologyProfile.SHIFT_WORKER -> RestorationWeights(hrvWeight = 0.50f, rhrWeight = 0.50f)
        }
    }

    private fun createEmergencyFlagThresholds(profile: PhysiologyProfile): EmergencyFlagThresholds {
        // Profile-agnostic for now; can be extended per profile if needed
        return EmergencyFlagThresholds()
    }

    private fun createCircadianConsistencyConfig(
        profile: PhysiologyProfile,
        circadianOverride: Int?,
        evaluationDays: Int,
        baselineDays: Int,
    ): CircadianConsistencyConfig {
        val strategy = CircadianStrategyFactory.getStrategy(profile)
        val threshold = strategy.determineThreshold(profile, circadianOverride)

        return CircadianConsistencyConfig(
            thresholdMinutes = threshold,
            useShiftWorkerMode = profile == PhysiologyProfile.SHIFT_WORKER,
            evaluationDays = evaluationDays,
            baselineDays = baselineDays,
        )
    }

    private fun createAuditTrail(daysSinceInstall: Int, currentDate: LocalDate): AuditTrail {
        return AuditTrailFactory.create(daysSinceInstall, currentDate)
    }

}
