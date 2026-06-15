package app.readylytics.health.domain.scoring

/**
 * Selects which heart-rate data source feeds a given load computation.
 *
 * - [WORKOUT_ONLY]: TRIMP/PAI/load are derived only from logged workout sessions.
 * - [EVERYDAY_HEART_RATE]: TRIMP/PAI/load are derived from continuous everyday heart rate data.
 */
enum class LoadSourceMode { WORKOUT_ONLY, EVERYDAY_HEART_RATE }
