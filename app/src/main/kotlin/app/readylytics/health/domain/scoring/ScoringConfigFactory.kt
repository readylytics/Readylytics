package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.circadian.CircadianThresholdDefaults
import app.readylytics.health.domain.scoring.components.AuditTrail
import app.readylytics.health.domain.scoring.components.AuditTrailFactory
import app.readylytics.health.domain.scoring.components.CircadianConsistencyConfig
import app.readylytics.health.domain.scoring.components.EmergencyFlagThresholds
import app.readylytics.health.domain.scoring.components.RestorationWeights
import app.readylytics.health.domain.scoring.components.SleepArchitectureTargetFactory
import app.readylytics.health.domain.scoring.components.SleepArchitectureTargets
import app.readylytics.health.util.SecureLogger
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
            val sleepTargets = SleepArchitectureTargetFactory.create(userPreferences.age)
            val emergencyFlags = createEmergencyFlagThresholds(userPreferences.physiologyProfile)
            val circadianConsistency =
                createCircadianConsistencyConfig(
                    userPreferences.physiologyProfile,
                    circadianOverride,
                    userPreferences.consistencyEvaluationDays,
                    userPreferences.consistencyBaselineDays,
                )

            val auditTrail = createAuditTrail(daysSinceInstall, currentDate)
            val hrvSaturationZ = hrvSaturationZForProfile(userPreferences.physiologyProfile)

            // Use SHA256 hash of configuration parameters for stable, deterministic identifier
            // This ensures consistency across JVM versions and app updates
            val paramsHash =
                computeConfigHash(restoration, sleepTargets, emergencyFlags, circadianConsistency, hrvSaturationZ)

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
                    hrvSaturationZ = hrvSaturationZ,
                )

            return config
        }

        private fun createRestorationWeights(profile: PhysiologyProfile): RestorationWeights =
            when (profile) {
                PhysiologyProfile.ATHLETE -> RestorationWeights(hrvWeight = 0.70f, rhrWeight = 0.30f)
                PhysiologyProfile.ACTIVE -> RestorationWeights(hrvWeight = 0.60f, rhrWeight = 0.40f)
                PhysiologyProfile.SEDENTARY -> RestorationWeights(hrvWeight = 0.50f, rhrWeight = 0.50f)
            }

        private fun createEmergencyFlagThresholds(profile: PhysiologyProfile): EmergencyFlagThresholds =
            when (profile) {
                PhysiologyProfile.ATHLETE ->
                    EmergencyFlagThresholds(
                        strongRecoveryZHrvThreshold = 1.2f,
                        illnessZHrvThreshold = -1.2f,
                    )
                PhysiologyProfile.ACTIVE ->
                    EmergencyFlagThresholds(
                        strongRecoveryZHrvThreshold = 1.5f,
                        illnessZHrvThreshold = -1.5f,
                    )
                PhysiologyProfile.SEDENTARY ->
                    EmergencyFlagThresholds(
                        strongRecoveryZHrvThreshold = 2.0f,
                        illnessZHrvThreshold = -2.0f,
                    )
            }

        internal fun hrvSaturationZForProfile(profile: PhysiologyProfile): Float =
            when (profile) {
                PhysiologyProfile.ATHLETE -> 1.2f
                PhysiologyProfile.ACTIVE -> 1.5f
                PhysiologyProfile.SEDENTARY -> 2.0f
            }

        private fun createCircadianConsistencyConfig(
            profile: PhysiologyProfile,
            circadianOverride: Int?,
            evaluationDays: Int,
            baselineDays: Int,
        ): CircadianConsistencyConfig {
            val threshold = CircadianThresholdDefaults.resolveThreshold(profile, circadianOverride)

            return CircadianConsistencyConfig(
                thresholdMinutes = threshold,
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
            hrvSaturationZ: Float = ScoringConstants.HRV_SCORE_SATURATION_Z,
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

            // Schema version
            update(CONFIG_SCHEMA_VERSION)

            // RestorationWeights
            update(restoration.hrvWeight)
            update(restoration.rhrWeight)

            // SleepArchitectureTargets
            update(sleepTargets.deepPercentage)
            update(sleepTargets.remPercentage)

            // EmergencyFlagThresholds
            update(emergencyFlags.strongRecoveryZHrvThreshold)
            update(emergencyFlags.strongRecoveryZRhrThreshold)
            update(emergencyFlags.illnessZHrvThreshold)
            update(emergencyFlags.illnessZRhrThreshold)
            update(emergencyFlags.illnessRhrDeltaBpm)

            // CircadianConsistencyConfig
            update(circadianConsistency.thresholdMinutes)
            update(circadianConsistency.evaluationDays)
            update(circadianConsistency.baselineDays)

            // HRV saturation threshold (profile-tiered)
            update(hrvSaturationZ)

            // Log only hash for debugging
            SecureLogger.debugEvent("Computing config hash (version: $CONFIG_SCHEMA_VERSION)")

            val hash = digest.digest()
            return hash.take(4).foldIndexed(0) { i, acc, byte ->
                acc or ((byte.toInt() and 0xFF) shl (i * 8))
            }
        }
    }
