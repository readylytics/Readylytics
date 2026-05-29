package com.gregor.lauritz.healthdashboard.domain.dashboard

import kotlinx.serialization.Serializable

@Serializable
enum class CardId {
    SLEEP_SCORE,
    READINESS,
    STEPS,
    HRV,
    SLEEP_RHR,
    SLEEP_DURATION,
    SLEEP_ARCHITECTURE,
    STRAIN_RATIO,
    PAI_DAILY,
    CIRCADIAN_CONSISTENCY,
    RESTING_HR,
    RECOVERY_INDEX,
    ACUTE_CHRONIC_RATIO,
    SLEEP_EFFICIENCY,
    WEIGHT,
    BODY_FAT,
    BLOOD_PRESSURE,
}

@Serializable
data class CardConfiguration(
    val cardId: CardId,
    val isVisible: Boolean = true,
    val position: Int = 0,
)
