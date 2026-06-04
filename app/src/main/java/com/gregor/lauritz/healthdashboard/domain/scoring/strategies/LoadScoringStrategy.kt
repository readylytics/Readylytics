package com.gregor.lauritz.healthdashboard.domain.scoring.strategies

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Readiness
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Restoration
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants.Strain
import com.gregor.lauritz.healthdashboard.domain.scoring.components.EmergencyFlagThresholds
import com.gregor.lauritz.healthdashboard.domain.util.mean
import com.gregor.lauritz.healthdashboard.domain.util.median
import com.gregor.lauritz.healthdashboard.domain.util.stdev
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

@Singleton
class LoadScoringStrategy
    @Inject
    constructor() {
        fun computeLoadScore(sr: Float): Float {
            if (sr <= Strain.SR_SWEET_SPOT_MAX) return Strain.OPTIMAL_SWEET_SPOT_SCORE
            val excess = sr - Strain.SR_SWEET_SPOT_MAX
            return (100f * exp(-Strain.QUADRATIC_PENALTY_K * excess * excess)).coerceIn(0f, 100f)
        }

        fun hrvSigma(
            lnHrvValues: List<Float>,
            sigmaPrior: Float,
        ): Float {
            val n = lnHrvValues.size
            val w =
                (
                    (n - ScoringConstants.HRV_SIGMA_BLEND_MIN_N).toFloat() /
                        (ScoringConstants.HRV_SIGMA_BLEND_MAX_N - ScoringConstants.HRV_SIGMA_BLEND_MIN_N)
                ).coerceIn(0f, 1f)
            val blended = w * lnHrvValues.stdev() + (1f - w) * sigmaPrior
            return blended.coerceAtLeast(Restoration.MIN_LN_SIGMA)
        }

        fun computeHrvZScore(
            currentRmssdMs: Float,
            muHistory: List<Float>,
            sigmaHistory: List<Float>,
            sigmaPrior: Float = PhysiologyProfile.GENERAL.lnSigmaPrior,
            baselineOverride: Float? = null,
            frozenLnMu: Float? = null,
            frozenLnSigma: Float? = null,
        ): Float? {
            if (currentRmssdMs <= 0f ||
                (frozenLnMu == null && baselineOverride == null && muHistory.isEmpty())
            ) {
                return null
            }
            val lnMuHistory = muHistory.map { ln(it.coerceAtLeast(0.001f)) }
            val lnSigmaHistory = sigmaHistory.map { ln(it.coerceAtLeast(0.001f)) }
            val lnToday = ln(currentRmssdMs.coerceAtLeast(0.001f))
            val mu =
                when {
                    frozenLnMu != null -> frozenLnMu
                    baselineOverride != null -> ln(baselineOverride.coerceAtLeast(0.001f))
                    else -> lnMuHistory.mean()
                }
            val sigma = frozenLnSigma ?: hrvSigma(lnSigmaHistory, sigmaPrior)
            return (lnToday - mu) / sigma
        }

        fun computeHrvScore(
            z: Float,
            saturationZ: Float = ScoringConstants.HRV_SCORE_SATURATION_Z,
        ): Float {
            val adjustedZ =
                if (z > saturationZ) {
                    saturationZ +
                        ScoringConstants.HRV_SCORE_SATURATION_SLOPE * (z - saturationZ)
                } else {
                    z
                }
            return (50f + 25f * adjustedZ).coerceIn(0f, 100f)
        }

        fun computeRhrZScore(
            currentRhrBpm: Float,
            rhrHistory: List<Int>,
            baselineOverride: Float?,
        ): Float? {
            if (rhrHistory.isEmpty() && baselineOverride == null) return null
            val mu = baselineOverride ?: rhrHistory.median()
            val sigma =
                rhrHistory
                    .stdev()
                    .takeIf { it > 0f } ?: (mu * 0.05f).coerceAtLeast(1f)
            return (currentRhrBpm - mu) / sigma
        }

        fun computeRecoveryFlags(
            zLnHrv: Float?,
            zRhr: Float?,
            rhrDeltaBpm: Float?,
            yesterdayZLnHrv: Float?,
            yesterdayZRhr: Float?,
            hrvMissing: Boolean,
            stagesSuspicious: Boolean,
            isLateNadir: Boolean,
            isCalibrating: Boolean,
            emergencyFlags: EmergencyFlagThresholds?,
        ): Set<RecoveryFlag> {
            val flags = mutableSetOf<RecoveryFlag>()
            if (isCalibrating) flags += RecoveryFlag.CALIBRATING
            if (hrvMissing) flags += RecoveryFlag.HRV_MISSING
            if (stagesSuspicious) flags += RecoveryFlag.STAGES_MISSING
            if (isLateNadir) flags += RecoveryFlag.NADIR_DELAYED

            val thresholds = emergencyFlags ?: EmergencyFlagThresholds()

            if (zLnHrv != null && zRhr != null) {
                val todayOverreaching =
                    zLnHrv > thresholds.overreachingZHrvThreshold &&
                        zRhr < thresholds.overreachingZRhrThreshold
                val prevOverreaching =
                    yesterdayZLnHrv != null &&
                        yesterdayZRhr != null &&
                        yesterdayZLnHrv > thresholds.overreachingZHrvThreshold &&
                        yesterdayZRhr < thresholds.overreachingZRhrThreshold
                if (todayOverreaching && prevOverreaching) flags += RecoveryFlag.OVERREACHING

                val todayIllness =
                    zLnHrv < thresholds.illnessZHrvThreshold &&
                        (
                            rhrDeltaBpm != null &&
                                rhrDeltaBpm >= thresholds.illnessRhrDeltaBpm ||
                                zRhr >= thresholds.illnessZRhrThreshold
                        )
                val prevIllness =
                    yesterdayZLnHrv != null &&
                        yesterdayZRhr != null &&
                        yesterdayZLnHrv < thresholds.illnessZHrvThreshold &&
                        yesterdayZRhr >= thresholds.illnessZRhrThreshold
                if (todayIllness && prevIllness) flags += RecoveryFlag.ILLNESS_ONSET
            }
            return flags
        }

        fun computeReadinessScore(
            sRest: Float,
            sleepScore: Float,
            loadScore: Float,
            recoveryFlags: Set<RecoveryFlag>,
        ): Float {
            var rs =
                Readiness.WEIGHT_RESTORATION * sRest +
                    Readiness.WEIGHT_SLEEP * sleepScore +
                    Readiness.WEIGHT_LOAD * loadScore

            if (RecoveryFlag.OVERREACHING in recoveryFlags) {
                rs = rs.coerceAtMost(Readiness.OVERREACHING_MAX_SCORE)
            }
            if (RecoveryFlag.ILLNESS_ONSET in recoveryFlags) {
                rs = rs.coerceAtMost(Readiness.ILLNESS_MAX_SCORE)
            }

            return rs.coerceIn(0f, 100f)
        }

        fun isLateNadir(
            minHrTimestampMs: Long,
            sessionStartMs: Long,
            durationMinutes: Int,
        ): Boolean {
            if (durationMinutes <= 0) return false
            val sessionDurationMs = durationMinutes * 60 * 1000L
            return (minHrTimestampMs - sessionStartMs) >
                (sessionDurationMs * Restoration.LATE_NADIR_THRESHOLD)
        }

        fun validateNight(
            rmssdMs: Float?,
            rhrBpm: Float?,
            durationMinutes: Int,
            deepMinutes: Int,
            remMinutes: Int,
            hrCoverageValid: Boolean = true,
        ): ScoringCalculator.NightValidationResult {
            val rmssdValid =
                rmssdMs != null &&
                    rmssdMs in ScoringConstants.MIN_VALID_RMSSD_MS..ScoringConstants.MAX_VALID_RMSSD_MS
            val rhrValid =
                rhrBpm == null ||
                    rhrBpm in ScoringConstants.MIN_VALID_SLEEP_RHR..ScoringConstants.MAX_VALID_SLEEP_RHR
            val durationValid = durationMinutes >= ScoringConstants.MIN_VALID_SLEEP_DURATION_MINUTES

            val deepFrac = if (durationMinutes > 0) deepMinutes / durationMinutes.toFloat() else 0f
            val remFrac = if (durationMinutes > 0) remMinutes / durationMinutes.toFloat() else 0f
            val stagesInvalid =
                deepFrac > ScoringConstants.MAX_VALID_DEEP_FRACTION ||
                    remFrac > ScoringConstants.MAX_VALID_REM_FRACTION
            val stagesSuspicious =
                !stagesInvalid &&
                    (deepFrac + remFrac) > ScoringConstants.MAX_VALID_DEEP_REM_SUM

            return ScoringCalculator.NightValidationResult(
                rmssdValid = rmssdValid,
                rhrValid = rhrValid,
                durationValid = durationValid,
                stagesValid = !stagesInvalid,
                stagesSuspicious = stagesSuspicious,
                hrCoverageValid = hrCoverageValid,
            )
        }
    }
