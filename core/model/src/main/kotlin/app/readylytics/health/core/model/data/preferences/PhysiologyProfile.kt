package app.readylytics.health.core.model.data.preferences

enum class PhysiologyProfile(
    val lnSigmaPrior: Float,
    val defaultSleepGoalHours: Float,
    val banisterMultiplier: Float,
    val defaultChengBeta: Float,
    val defaultItrimB: Float,
) {
    ATHLETE(
        lnSigmaPrior = 0.10f,
        defaultSleepGoalHours = 9.0f,
        banisterMultiplier = 1.00f,
        defaultChengBeta = 0.07f,
        defaultItrimB = 2.9f,
    ),
    ACTIVE(
        lnSigmaPrior = 0.15f,
        defaultSleepGoalHours = 8.0f,
        banisterMultiplier = 1.00f,
        defaultChengBeta = 0.09f,
        defaultItrimB = 2.1f,
    ),
    SEDENTARY(
        lnSigmaPrior = 0.20f,
        defaultSleepGoalHours = 7.5f,
        banisterMultiplier = 1.00f,
        defaultChengBeta = 0.11f,
        defaultItrimB = 1.5f,
    ),
}

/**
 * Banister multipliers shipped per profile before the normalization to 1.0. The one-time
 * DataStore migration (TrimpMigrationHelper) treats a stored value equal to the legacy default
 * for the user's stored profile as an accepted default rather than an explicit override, and
 * normalizes it. Never reuse these as live defaults.
 */
object LegacyBanisterMultipliers {
    const val ATHLETE = 1.00f
    const val ACTIVE = 1.35f
    const val SEDENTARY = 1.75f

    fun forProfile(profile: PhysiologyProfile): Float =
        when (profile) {
            PhysiologyProfile.ATHLETE -> ATHLETE
            PhysiologyProfile.ACTIVE -> ACTIVE
            PhysiologyProfile.SEDENTARY -> SEDENTARY
        }
}
