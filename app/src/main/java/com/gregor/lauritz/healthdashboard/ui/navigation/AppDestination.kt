package com.gregor.lauritz.healthdashboard.ui.navigation

import kotlinx.serialization.Serializable

sealed interface AppDestination {
    @Serializable
    data object Onboarding : AppDestination

    @Serializable
    data object Unavailable : AppDestination

    @Serializable
    data object Dashboard : AppDestination
}
