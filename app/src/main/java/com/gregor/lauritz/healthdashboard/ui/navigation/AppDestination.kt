package com.gregor.lauritz.healthdashboard.ui.navigation

import kotlinx.serialization.Serializable

sealed interface AppDestination {
    @Serializable
    data object About : AppDestination

    @Serializable
    data object Onboarding : AppDestination

    @Serializable
    data object Unavailable : AppDestination

    @Serializable
    data object MainShell : AppDestination

    @Serializable
    data class WorkoutDetail(
        val workoutId: String,
    ) : AppDestination

    @Serializable
    data object StepDetail : AppDestination

    @Serializable
    data object HeartRateDetail : AppDestination

    @Serializable
    data object WeightDetail : AppDestination

    @Serializable
    data object BodyFatDetail : AppDestination

    @Serializable
    data object BloodPressureDetail : AppDestination
}
