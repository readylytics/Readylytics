package app.readylytics.health.core.scoring.domain.scoring.components

import app.readylytics.health.core.scoring.domain.scoring.components.SleepArchitectureTargetFactory
import app.readylytics.health.core.scoring.domain.scoring.components.SleepArchitectureTargets

object SleepArchitectureTargetFactory {
    private const val REFERENCE_AGE = 20f

    private const val DEEP_AT_REFERENCE_AGE = 0.21f
    private const val DEEP_DECLINE_PER_YEAR = 0.0016f
    private const val DEEP_MIN = 0.12f
    private const val DEEP_MAX = 0.22f

    private const val REM_AT_REFERENCE_AGE = 0.22f
    private const val REM_DECLINE_PER_YEAR = 0.0006f
    private const val REM_MIN = 0.18f
    private const val REM_MAX = 0.23f

    fun create(ageYears: Int): SleepArchitectureTargets {
        val yearsPastReference = ageYears.toFloat() - REFERENCE_AGE
        return SleepArchitectureTargets(
            deepPercentage =
                (DEEP_AT_REFERENCE_AGE - DEEP_DECLINE_PER_YEAR * yearsPastReference)
                    .coerceIn(DEEP_MIN, DEEP_MAX),
            remPercentage =
                (REM_AT_REFERENCE_AGE - REM_DECLINE_PER_YEAR * yearsPastReference)
                    .coerceIn(REM_MIN, REM_MAX),
        )
    }
}
