package com.gregor.lauritz.healthdashboard.domain.scoring.components

object SleepArchitectureTargetFactory {
    fun create(ageYears: Int, gender: String?): SleepArchitectureTargets {
        val base = when {
            ageYears in 18..29 -> SleepArchitectureTargets.AgeRange18_29()
            ageYears in 30..49 -> SleepArchitectureTargets.AgeRange30_49()
            ageYears in 50..59 -> SleepArchitectureTargets.AgeRange50_59()
            else -> SleepArchitectureTargets.AgeRange60Plus()
        }
        return base.applyGenderAdjustment(gender)
    }
}
