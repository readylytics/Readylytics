package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.InsightType

/**
 * Result of an [InsightRule] firing: the displayable insight type plus the
 * computed parameters needed to render its message.
 */
data class InsightFinding(
    val type: InsightType,
    val params: InsightParams,
)

/**
 * Typed payloads for parameterized insight messages.
 */
sealed class InsightParams {
    data object None : InsightParams()

    data class CircadianShift(
        val bedtimeOffsetMinutes: Int,
    ) : InsightParams()

    data class HighStrainSleepDeficit(
        val strainRatio: Float,
        val sleepDeficitMinutes: Int,
    ) : InsightParams()

    data class LateNadirShortSleep(
        val sleepDurationMinutes: Int,
        val goalSleepMinutes: Int,
    ) : InsightParams()

    data class HrvSpo2(
        val zLnHrv: Float,
        val spo2: Float,
    ) : InsightParams()

    data class LateNadirElevatedRhr(
        val rhrDeltaBpm: Float,
    ) : InsightParams()

    data class BpElevatedStrain(
        val systolicDriftMmHg: Int,
        val strainRatio: Float,
    ) : InsightParams()

    data class PaiDepletionStrain(
        val totalPai: Float,
        val strainRatio: Float,
    ) : InsightParams()

    data class HrvDeclineStreak(
        val days: Int,
    ) : InsightParams()

    data class StepShortfall(
        val stepCount: Int,
        val stepGoal: Int,
    ) : InsightParams()

    data class PaiWeeklyShortfall(
        val weeklyPai: Float,
        val target: Float,
    ) : InsightParams()

    data class WeightDrift(
        val deltaKg: Float,
        val percent: Float,
    ) : InsightParams()
}
