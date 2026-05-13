package com.gregor.lauritz.healthdashboard.domain.scoring.components

object SleepArchitectureTargetFactory {
    fun create(
        ageYears: Int,
        gender: String?,
    ): SleepArchitectureTargets {
        val base =
            when {
                ageYears in 18..29 -> SleepArchitectureTargets.AgeRange18To29()
                ageYears in 30..49 -> SleepArchitectureTargets.AgeRange30To49()
                ageYears in 50..69 -> SleepArchitectureTargets.AgeRange50To69()
                else -> SleepArchitectureTargets.AgeRange60Plus()
            }
        return base.applyGenderAdjustment(gender)
    }
}
