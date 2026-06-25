package app.readylytics.health.domain.scoring.components

sealed class SleepArchitectureTargets {
    abstract val deepPercentage: Float
    abstract val remPercentage: Float

    // Age 18-29: REF: Ohayon 2004 Sleep 27:1255
    data class AgeRange18To29(
        override val deepPercentage: Float = 0.20f,
        override val remPercentage: Float = 0.22f,
    ) : SleepArchitectureTargets()

    // Age 30-49: REF: Ohayon 2004
    data class AgeRange30To49(
        override val deepPercentage: Float = 0.18f,
        override val remPercentage: Float = 0.21f,
    ) : SleepArchitectureTargets()

    // Age 50-59: REF: Ohayon 2004
    data class AgeRange50To59(
        override val deepPercentage: Float = 0.15f,
        override val remPercentage: Float = 0.20f,
    ) : SleepArchitectureTargets()

    // Age 60+: REF: Ohayon 2004
    data class AgeRange60Plus(
        override val deepPercentage: Float = 0.12f,
        override val remPercentage: Float = 0.19f,
    ) : SleepArchitectureTargets()
}
