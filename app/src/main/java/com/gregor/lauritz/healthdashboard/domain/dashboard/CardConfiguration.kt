package com.gregor.lauritz.healthdashboard.domain.dashboard

import kotlinx.serialization.Serializable

enum class ScreenType {
    DASHBOARD,
    SLEEP,
    WORKOUTS,
}

@Serializable
enum class CardId {
    SLEEP_SCORE,
    READINESS,
    STEPS,
    HRV,
    RHR,
    SLEEP_DURATION,
    SLEEP_ARCHITECTURE,
    LOAD_SCORE,
    STRAIN_RATIO,
    PAI_DAILY,
    CIRCADIAN_CONSISTENCY,
    RESTING_HR,
    RECOVERY_INDEX,
    ACUTE_CHRONIC_RATIO,
}

@Serializable
data class CardConfiguration(
    val cardId: CardId,
    val isVisible: Boolean = true,
    val position: Int = 0,
)

data class CardLayout(
    val screenType: ScreenType,
    val cards: List<CardConfiguration> = emptyList(),
)
