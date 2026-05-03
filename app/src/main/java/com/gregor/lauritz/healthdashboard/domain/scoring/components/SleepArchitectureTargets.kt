package com.gregor.lauritz.healthdashboard.domain.scoring.components

sealed class SleepArchitectureTargets {
    abstract val deepPercentage: Float
    abstract val remPercentage: Float

    // Age 18-29: REF: Ohayon 2004 Sleep 27:1255
    data class AgeRange18_29(
        override val deepPercentage: Float = 0.20f,
        override val remPercentage: Float = 0.22f,
    ) : SleepArchitectureTargets()

    // Age 30-49: REF: Ohayon 2004
    data class AgeRange30_49(
        override val deepPercentage: Float = 0.18f,
        override val remPercentage: Float = 0.21f,
    ) : SleepArchitectureTargets()

    // Age 50-59: REF: Ohayon 2004; Boulos 2019 Lancet Respir Med
    data class AgeRange50_59(
        override val deepPercentage: Float = 0.16f,
        override val remPercentage: Float = 0.20f,
    ) : SleepArchitectureTargets()

    // Age 60+: REF: Ohayon 2004
    data class AgeRange60Plus(
        override val deepPercentage: Float = 0.12f,
        override val remPercentage: Float = 0.18f,
    ) : SleepArchitectureTargets()
}

fun SleepArchitectureTargets.applyGenderAdjustment(gender: String?): SleepArchitectureTargets {
    // For females 50-59: add 30 min equivalent to deep sleep target (≈0.02 of typical 8h sleep)
    // REF: Hormonal changes, menopause-related disruption
    return if (gender?.lowercase() == "female" && this is SleepArchitectureTargets.AgeRange50_59) {
        this.copy(deepPercentage = this.deepPercentage + 0.02f)
    } else {
        this
    }
}
