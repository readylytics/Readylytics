package app.readylytics.health.core.scoring.domain.scoring.components

import app.readylytics.health.core.model.domain.model.RecoveryFlag

data class RecoveryFlagContext(
    val zLnHrv: Float?,
    val zRhr: Float?,
    val rhrDeltaBpm: Float?,
    val yesterdayZLnHrv: Float?,
    val yesterdayZRhr: Float?,
    val hrvMissing: Boolean,
    val stagesSuspicious: Boolean,
    val isLateNadir: Boolean,
    val isCalibrating: Boolean,
    val emergencyFlags: EmergencyFlagThresholds?,
    val yesterdayTrimp: Float? = null,
    val yesterdayHrv: Float? = null,
    val currentHrv: Float? = null,
    val hrvOptimalThreshold: Float = 1.0f,
    val isCurrentHrvOptimal: Boolean = false,
    val isCurrentRhrOptimal: Boolean = false,
    val isPreviousHrvOptimal: Boolean = false,
)

object RecoveryFlagEvaluator {
    fun evaluate(context: RecoveryFlagContext): Set<RecoveryFlag> {
        val flags = mutableSetOf<RecoveryFlag>()
        if (context.isCalibrating) flags += RecoveryFlag.CALIBRATING
        if (context.hrvMissing) flags += RecoveryFlag.HRV_MISSING
        if (context.stagesSuspicious) flags += RecoveryFlag.SUSPICIOUS_STAGE_RATIO
        if (context.isLateNadir) flags += RecoveryFlag.NADIR_DELAYED

        val thresholds = context.emergencyFlags ?: EmergencyFlagThresholds()

        evaluateEmergencyFlags(context, thresholds, flags)

        if (!context.hrvMissing) {
            computeWorkoutAndRestFlags(context)?.let { flags.add(it) }
        }

        return flags
    }

    private fun evaluateEmergencyFlags(
        ctx: RecoveryFlagContext,
        thresholds: EmergencyFlagThresholds,
        flags: MutableSet<RecoveryFlag>,
    ) {
        val zLnHrv = ctx.zLnHrv ?: return
        val zRhr = ctx.zRhr ?: return

        val isIllness = checkIllness(ctx, thresholds, zLnHrv, zRhr)
        if (isIllness) flags += RecoveryFlag.ILLNESS_ONSET

        val isStrongRecovery = checkStrongRecovery(ctx, thresholds, zLnHrv, zRhr)
        if (isStrongRecovery && RecoveryFlag.ILLNESS_ONSET !in flags) {
            flags += RecoveryFlag.STRONG_RECOVERY_SIGNAL
        }
    }

    private fun checkIllness(
        ctx: RecoveryFlagContext,
        thresholds: EmergencyFlagThresholds,
        zLnHrv: Float,
        zRhr: Float,
    ): Boolean {
        val rhrDeltaExceeded = ctx.rhrDeltaBpm != null && ctx.rhrDeltaBpm >= thresholds.illnessRhrDeltaBpm
        val zRhrExceeded = zRhr >= thresholds.illnessZRhrThreshold
        val todayIllness = zLnHrv < thresholds.illnessZHrvThreshold && (rhrDeltaExceeded || zRhrExceeded)

        val prevIllness =
            ctx.yesterdayZLnHrv != null &&
                ctx.yesterdayZRhr != null &&
                ctx.yesterdayZLnHrv < thresholds.illnessZHrvThreshold &&
                ctx.yesterdayZRhr >= thresholds.illnessZRhrThreshold

        return todayIllness && prevIllness
    }

    private fun checkStrongRecovery(
        ctx: RecoveryFlagContext,
        thresholds: EmergencyFlagThresholds,
        zLnHrv: Float,
        zRhr: Float,
    ): Boolean {
        val todayStrongRecovery =
            zLnHrv > thresholds.strongRecoveryZHrvThreshold &&
                zRhr < thresholds.strongRecoveryZRhrThreshold
        val prevStrongRecovery =
            ctx.yesterdayZLnHrv != null &&
                ctx.yesterdayZRhr != null &&
                ctx.yesterdayZLnHrv > thresholds.strongRecoveryZHrvThreshold &&
                ctx.yesterdayZRhr < thresholds.strongRecoveryZRhrThreshold

        return todayStrongRecovery && prevStrongRecovery
    }

    private fun computeWorkoutAndRestFlags(ctx: RecoveryFlagContext): RecoveryFlag? {
        val trimp = ctx.yesterdayTrimp
        val currHrv = ctx.currentHrv
        val prevHrv = ctx.yesterdayHrv
        val isValid = trimp != null && currHrv != null && prevHrv != null && prevHrv > 0f
        return if (isValid) {
            computeWorkoutImpactFlag(ctx, trimp!!, currHrv!!, prevHrv!!)
                ?: computeRestDayFlag(ctx, trimp, currHrv, prevHrv)
        } else {
            null
        }
    }

    private fun computeWorkoutImpactFlag(
        ctx: RecoveryFlagContext,
        yesterdayTrimp: Float,
        currentHrv: Float,
        yesterdayHrv: Float,
    ): RecoveryFlag? {
        if (yesterdayTrimp < 120f) return null
        val hrvDropThreshold = (2f - ctx.hrvOptimalThreshold).coerceIn(0f, 1f)
        val hrvDropped = currentHrv < yesterdayHrv * hrvDropThreshold
        val conditionsMet = !ctx.isCurrentHrvOptimal && !ctx.isCurrentRhrOptimal && hrvDropped
        return if (conditionsMet) RecoveryFlag.WORKOUT_IMPACT else null
    }

    private fun computeRestDayFlag(
        ctx: RecoveryFlagContext,
        yesterdayTrimp: Float,
        currentHrv: Float,
        yesterdayHrv: Float,
    ): RecoveryFlag? {
        if (yesterdayTrimp >= 10f) return null
        val significantIncrease = currentHrv >= yesterdayHrv * ctx.hrvOptimalThreshold
        val newlyOptimal = ctx.isCurrentHrvOptimal && !ctx.isPreviousHrvOptimal
        return when {
            significantIncrease || newlyOptimal -> RecoveryFlag.REST_DAY_SUCCESS
            !ctx.isCurrentHrvOptimal -> RecoveryFlag.REST_DAY_NO_IMPACT
            else -> null
        }
    }
}
