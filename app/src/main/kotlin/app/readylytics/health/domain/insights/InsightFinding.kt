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
}
