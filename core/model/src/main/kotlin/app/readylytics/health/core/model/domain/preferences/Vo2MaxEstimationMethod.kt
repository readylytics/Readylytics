package app.readylytics.health.core.model.domain.preferences

/**
 * Which resting estimator computes the "estimated" VO2 Max when
 * [Vo2MaxSourceMode] resolves to an estimate (ESTIMATED_ONLY or AUTO fallback).
 *
 * [HR_RATIO] is the Uth et al. (2004) heart-rate-ratio method. [MATERKO_ADAPTED]
 * is the experimental Materko-adapted resting HR + HRV estimator; see
 * `MaterkoAdaptedVo2MaxCalculator` for the documented deviations from the
 * published model.
 */
enum class Vo2MaxEstimationMethod {
    HR_RATIO,
    MATERKO_ADAPTED,
}
