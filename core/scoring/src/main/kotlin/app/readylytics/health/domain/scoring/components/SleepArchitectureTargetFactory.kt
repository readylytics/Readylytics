package app.readylytics.health.domain.scoring.components

object SleepArchitectureTargetFactory {
    fun create(ageYears: Int): SleepArchitectureTargets =
        when {
            ageYears in 18..29 -> SleepArchitectureTargets.AgeRange18To29()
            ageYears in 30..49 -> SleepArchitectureTargets.AgeRange30To49()
            ageYears in 50..59 -> SleepArchitectureTargets.AgeRange50To59()
            else -> SleepArchitectureTargets.AgeRange60Plus()
        }
}
