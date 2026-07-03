package app.readylytics.health.domain.validation

/**
 * Physiological bounds for human heart rate measurements.
 *
 * These constants define the absolute input-validation range accepted
 * across the app (user-entered HR fields, zone boundaries, etc.).
 * They intentionally match [SettingsValidators.HEART_RATE_RULE].
 */
object HeartRateConstants {
    /** Minimum plausible heart rate a user may enter (bpm). */
    const val MIN_HEART_RATE = 1

    /** Maximum plausible heart rate a user may enter (bpm). */
    const val MAX_HEART_RATE = 220
}
