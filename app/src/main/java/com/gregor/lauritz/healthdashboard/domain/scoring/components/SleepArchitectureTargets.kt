package com.gregor.lauritz.healthdashboard.domain.scoring.components

import com.gregor.lauritz.healthdashboard.data.preferences.Gender

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
        override val remPercentage: Float = 0.22f,
    ) : SleepArchitectureTargets()

    // Age 50-69: REF: Ohayon 2004
    data class AgeRange50To69(
        override val deepPercentage: Float = 0.15f,
        override val remPercentage: Float = 0.21f,
    ) : SleepArchitectureTargets()

    // Age 60+: REF: Ohayon 2004
    data class AgeRange60Plus(
        override val deepPercentage: Float = 0.12f,
        override val remPercentage: Float = 0.18f,
    ) : SleepArchitectureTargets()
}

fun SleepArchitectureTargets.applyGenderAdjustment(gender: Gender?): SleepArchitectureTargets {
    // For females 50-59: add 30 min equivalent to deep sleep target (≈0.02 of typical 8h sleep)
    // REF: Hormonal changes, menopause-related disruption
    return if (gender == Gender.FEMALE && this is SleepArchitectureTargets.AgeRange50To69) {
        this.copy(deepPercentage = this.deepPercentage + 0.02f)
    } else {
        this
    }
}
