package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.circadian.CircadianStrategyFactory
import com.gregor.lauritz.healthdashboard.domain.scoring.components.AuditTrail
import com.gregor.lauritz.healthdashboard.domain.scoring.components.AuditTrailFactory
import com.gregor.lauritz.healthdashboard.domain.scoring.components.CircadianConsistencyConfig
import com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds
import com.gregor.lauritz.healthdashboard.domain.scoring.components.RestorationWeights
import com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargetFactory
import com.gregor.lauritz.healthdashboard.domain.scoring.components.SleepArchitectureTargets
import com.gregor.lauritz.healthdashboard.util.SecureLogger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoringConfigFactory
    @Inject
    constructor() {
        companion object {
            /**
             * Configuration schema version.
             * MUST be incremented when adding/removing fields from config classes.
             */
            private const val CONFIG_SCHEMA_VERSION = 2
        }

        fun build(
            userPreferences: UserPreferences,
            installDate: LocalDate,
            currentDate: LocalDate,
            circadianOverride: Int? = null,
        ): ScoringConfig {
            val daysSinceInstall =
                ChronoUnit.DAYS
                    .between(installDate, currentDate)
                    .toInt()
                    .coerceAtLeast(0)

            val restoration = createRestorationWeights(userPreferences.physiologyProfile)
            val sleepTargets = SleepArchitectureTargetFactory.create(userPreferences.age, userPreferences.gender)
            val emergencyFlags = createEmergencyFlagThresholds(userPreferences.physiologyProfile)
            val circadianConsistency =
                createCircadianConsistencyConfig(
                    userPreferences.physiologyProfile,
                    circadianOverride,
                    userPreferences.consistencyEvaluationDays,
                    userPreferences.consistencyBaselineDays,
                )

            val auditTrail = createAuditTrail(daysSinceInstall, currentDate)

            // Use SHA256 hash of configuration parameters for stable, deterministic identifier
            // This ensures consistency across JVM versions and app updates
            val paramsHash = computeConfigHash(restoration, sleepTargets, emergencyFlags, circadianConsistency)

            val config =
                ScoringConfig(
                    restoration = restoration,
                    sleepTargets = sleepTargets,
                    emergencyFlags = emergencyFlags,
                    circadianConsistency = circadianConsistency,
                    paiScalingFactor = userPreferences.paiScalingFactor,
                    auditTrail = auditTrail.copy(configHashCode = paramsHash),
                    trimpModel = userPreferences.trimpModel,
                    banisterMultiplier = userPreferences.banisterMultiplier,
                    chengBeta = userPreferences.chengBeta,
                    itrimB = userPreferences.itrimB,
                )

            return config
        }

        private fun createRestorationWeights(profile: PhysiologyProfile): RestorationWeights =
            when (profile) {
                PhysiologyProfile.ATHLETE -> RestorationWeights(hrvWeight = 0.70f, rhrWeight = 0.30f)
                PhysiologyProfile.ACTIVE -> RestorationWeights(hrvWeight = 0.60f, rhrWeight = 0.40f)
                PhysiologyProfile.GENERAL -> RestorationWeights(hrvWeight = 0.50f, rhrWeight = 0.50f)
                PhysiologyProfile.SEDENTARY -> RestorationWeights(hrvWeight = 0.50f, rhrWeight = 0.50f)
                PhysiologyProfile.SHIFT_WORKER -> RestorationWeights(hrvWeight = 0.50f, rhrWeight = 0.50f)
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

        private fun createAuditTrail(
            daysSinceInstall: Int,
            currentDate: LocalDate,
        ): AuditTrail = AuditTrailFactory.create(daysSinceInstall, currentDate)

        private fun computeConfigHash(
            restoration: RestorationWeights,
            sleepTargets: SleepArchitectureTargets,
            emergencyFlags: EmergencyFlagThresholds,
            circadianConsistency: CircadianConsistencyConfig,
        ): Int {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(4)

            fun update(value: Int) {
                buffer.clear()
                buffer.putInt(value)
                digest.update(buffer.array(), 0, 4)
            }

            fun update(value: Float) {
                buffer.clear()
                buffer.putFloat(value)
                digest.update(buffer.array(), 0, 4)
            }

            fun update(value: Boolean) {
                digest.update(if (value) 1.toByte() else 0.toByte())
            }

            // Schema version
            update(CONFIG_SCHEMA_VERSION)

            // RestorationWeights
            update(restoration.hrvWeight)
            update(restoration.rhrWeight)

            // SleepArchitectureTargets
            update(sleepTargets.deepPercentage)
            update(sleepTargets.remPercentage)

            // EmergencyFlagThresholds
            update(emergencyFlags.overreachingZHrvThreshold)
            update(emergencyFlags.overreachingZRhrThreshold)
            update(emergencyFlags.illnessZHrvThreshold)
            update(emergencyFlags.illnessZRhrThreshold)
            update(emergencyFlags.illnessRhrDeltaBpm)

            // CircadianConsistencyConfig
            update(circadianConsistency.thresholdMinutes)
            update(circadianConsistency.useShiftWorkerMode)
            update(circadianConsistency.evaluationDays)
            update(circadianConsistency.baselineDays)

            // Log only hash for debugging
            SecureLogger.debugEvent("Computing config hash (version: $CONFIG_SCHEMA_VERSION)")

            val hash = digest.digest()
            return hash.take(4).foldIndexed(0) { i, acc, byte ->
                acc or ((byte.toInt() and 0xFF) shl (i * 8))
            }
        }
    }
