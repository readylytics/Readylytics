package com.gregor.lauritz.healthdashboard.domain.model

/**
 * Translates a [ReadinessCappingReason] (or a healthy readiness score) into an
 * actionable training recommendation. The product surface uses this to display
 * a single human-readable line plus a structured action enum so navigation /
 * notifications can react without parsing strings.
 *
 * @property action Discrete training intensity bucket for the next 24 hours.
 * @property message One-sentence guidance shown to the user. Always present.
 * @property durationDays Suggested duration the recommendation applies for. Null
 *  when the action is a one-day evaluation (e.g. illness watch).
 * @property nextCheckTime UTC ms when the user should re-evaluate (e.g. tomorrow
 *  morning's reading). Null = no scheduled re-check.
 */
data class ReadinessRecommendation(
    val action: TrainingAction,
    val message: String,
    val durationDays: Int? = null,
    val nextCheckTime: Long? = null,
) {
    companion object {
        const val OVERREACHING_RECOMMENDATION_DAYS = 4
        const val ILLNESS_RECOMMENDATION_DAYS = 1
        const val EXTREME_LOAD_RECOMMENDATION_DAYS = 3
    }
}

/**
 * Coarse-grained training intensity bucket. Mapped from readiness score and
 * capping reason.
 *
 * Thresholds:
 * - FULL_EFFORT: score >= 80
 * - NORMAL: 60..79
 * - LIGHT_ACTIVITY: 40..59
 * - REST: < 40
 */
enum class TrainingAction {
    FULL_EFFORT,
    NORMAL,
    LIGHT_ACTIVITY,
    REST,
}
