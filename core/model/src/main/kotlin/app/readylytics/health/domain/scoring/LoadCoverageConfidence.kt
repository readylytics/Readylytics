package app.readylytics.health.domain.scoring

/**
 * Confidence in the "everyday" (non-workout, non-sleep) heart-rate load estimate, based on how
 * many waking minutes had at least one HR sample.
 */
enum class LoadCoverageConfidence { NONE, LOW, MEDIUM, HIGH }
