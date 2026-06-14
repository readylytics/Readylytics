package app.readylytics.health.data.preferences

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
        banisterMultiplier = 1.35f,
        defaultChengBeta = 0.09f,
        defaultItrimB = 2.1f,
    ),
    SEDENTARY(
        lnSigmaPrior = 0.20f,
        defaultSleepGoalHours = 7.5f,
        banisterMultiplier = 1.75f,
        defaultChengBeta = 0.11f,
        defaultItrimB = 1.5f,
    ),
}
