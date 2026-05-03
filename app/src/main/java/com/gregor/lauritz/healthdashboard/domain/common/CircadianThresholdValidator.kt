package com.gregor.lauritz.healthdashboard.domain.common

object CircadianThresholdValidator {
    const val MIN_THRESHOLD = 0
    const val MAX_THRESHOLD = 90

    fun validate(minutes: Int?): Result<Unit> {
        return when {
            minutes == null -> Result.success(Unit)
            minutes !in MIN_THRESHOLD..MAX_THRESHOLD -> {
                Result.failure(
                    IllegalArgumentException(
                        "Circadian threshold must be between $MIN_THRESHOLD and $MAX_THRESHOLD minutes, got $minutes"
                    )
                )
            }
            else -> Result.success(Unit)
        }
    }
}
