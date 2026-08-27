package app.readylytics.health.core.scoring.domain.scoring.strategies

import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.scoring.ScoringConstants.Readiness
import app.readylytics.health.core.model.domain.scoring.ScoringConstants.Restoration
import app.readylytics.health.core.model.domain.scoring.ScoringConstants.Strain
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.components.EmergencyFlagThresholds
import app.readylytics.health.core.scoring.domain.scoring.components.RecoveryFlagContext
import app.readylytics.health.core.scoring.domain.scoring.components.RecoveryFlagEvaluator
import app.readylytics.health.core.scoring.domain.util.mean
import app.readylytics.health.core.scoring.domain.util.median
import app.readylytics.health.core.scoring.domain.util.stdev
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
            sigmaPrior: Float = PhysiologyProfile.ACTIVE.lnSigmaPrior,
            baselineOverride: Float? = null,
            frozenLnMu: Float? = null,
            frozenLnSigma: Float? = null,
        ): Float? {
            val hasNoBaselineReference =
                frozenLnMu == null && baselineOverride == null && muHistory.isEmpty()
            if (currentRmssdMs <= 0f || hasNoBaselineReference) {
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
            frozenSigma: Float? = null,
        ): Float? {
            if (rhrHistory.isEmpty() && baselineOverride == null) return null
            val mu = baselineOverride ?: rhrHistory.median()
            val sigma =
                frozenSigma ?: rhrHistory
                    .takeIf { it.size > 1 }
                    ?.stdev()
                    ?.takeIf { it > 0f } ?: (mu * 0.05f).coerceAtLeast(1f)
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
            yesterdayTrimp: Float? = null,
            yesterdayHrv: Float? = null,
            currentHrv: Float? = null,
            hrvOptimalThreshold: Float = 1.0f,
            isCurrentHrvOptimal: Boolean = false,
            isCurrentRhrOptimal: Boolean = false,
            isPreviousHrvOptimal: Boolean = false,
        ): Set<RecoveryFlag> =
            RecoveryFlagEvaluator.evaluate(
                RecoveryFlagContext(
                    zLnHrv = zLnHrv,
                    zRhr = zRhr,
                    rhrDeltaBpm = rhrDeltaBpm,
                    yesterdayZLnHrv = yesterdayZLnHrv,
                    yesterdayZRhr = yesterdayZRhr,
                    hrvMissing = hrvMissing,
                    stagesSuspicious = stagesSuspicious,
                    isLateNadir = isLateNadir,
                    isCalibrating = isCalibrating,
                    emergencyFlags = emergencyFlags,
                    yesterdayTrimp = yesterdayTrimp,
                    yesterdayHrv = yesterdayHrv,
                    currentHrv = currentHrv,
                    hrvOptimalThreshold = hrvOptimalThreshold,
                    isCurrentHrvOptimal = isCurrentHrvOptimal,
                    isCurrentRhrOptimal = isCurrentRhrOptimal,
                    isPreviousHrvOptimal = isPreviousHrvOptimal,
                ),
            )

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
