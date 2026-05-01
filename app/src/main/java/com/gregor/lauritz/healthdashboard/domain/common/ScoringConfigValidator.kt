package com.gregor.lauritz.healthdashboard.domain.common

import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConfig

object ScoringConfigValidator {
    fun validate(config: ScoringConfig): Result<Unit> {
        val errors = mutableListOf<String>()

        // Validate restoration weights
        if (config.restoration.hrvWeight < 0f || config.restoration.rhrWeight < 0f) {
            errors.add("Restoration weights must be non-negative")
        }
        if (config.restoration.hrvWeight + config.restoration.rhrWeight == 0f) {
            errors.add("At least one restoration weight must be non-zero")
        }

        // Validate sleep targets
        if (config.sleepTargets.deepPercentage < 0f || config.sleepTargets.deepPercentage > 1f) {
            errors.add("Deep sleep percentage must be between 0 and 1")
        }
        if (config.sleepTargets.remPercentage < 0f || config.sleepTargets.remPercentage > 1f) {
            errors.add("REM percentage must be between 0 and 1")
        }

        // Validate circadian consistency
        if (config.circadianConsistency.thresholdMinutes < 0 && config.circadianConsistency.thresholdMinutes != Int.MAX_VALUE) {
            errors.add("Circadian threshold must be non-negative")
        }

        return if (errors.isEmpty()) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalArgumentException("ScoringConfig validation failed: ${errors.joinToString("; ")}")
            )
        }
    }
}
