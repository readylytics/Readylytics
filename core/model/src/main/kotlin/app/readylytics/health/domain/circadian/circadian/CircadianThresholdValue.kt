package app.readylytics.health.domain.circadian

/**
 * Validated circadian threshold value.
 * Ensures threshold is within safe physiological bounds: 0-90 minutes.
 */
data class CircadianThresholdValue(
    val minutes: Int,
) {
    init {
        require(minutes in VALID_RANGE) {
            "Threshold must be between $MIN_MINUTES-$MAX_MINUTES minutes, got $minutes"
        }
    }

    companion object {
        const val MIN_MINUTES = 0
        const val MAX_MINUTES = 90
        val VALID_RANGE = MIN_MINUTES..MAX_MINUTES

        /**
         * Safely create a validated threshold value.
         * Returns null if minutes is null.
         * Returns failure if minutes is out of valid range.
         */
        fun tryCreate(minutes: Int?): Result<CircadianThresholdValue?> =
            if (minutes == null) {
                Result.success(null)
            } else {
                runCatching { CircadianThresholdValue(minutes) }
            }

        /**
         * Create with validation and error recovery.
         * Returns nearest valid value if input is out of range.
         */
        fun createOrClamp(minutes: Int?): CircadianThresholdValue? =
            minutes?.let {
                CircadianThresholdValue(it.coerceIn(VALID_RANGE))
            }
    }
}
