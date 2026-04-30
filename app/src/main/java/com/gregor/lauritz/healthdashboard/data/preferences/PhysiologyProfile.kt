package com.gregor.lauritz.healthdashboard.data.preferences

enum class PhysiologyProfile(
    val lnSigmaPrior: Float,
    val defaultSleepGoalHours: Float,
) {
    ATHLETE(lnSigmaPrior = 0.10f, defaultSleepGoalHours = 9.0f),
    ACTIVE(lnSigmaPrior = 0.15f, defaultSleepGoalHours = 8.0f),
    GENERAL(lnSigmaPrior = 0.18f, defaultSleepGoalHours = 8.0f),
    SEDENTARY(lnSigmaPrior = 0.20f, defaultSleepGoalHours = 7.5f),
    SHIFT_WORKER(lnSigmaPrior = 0.20f, defaultSleepGoalHours = 7.5f),
}
