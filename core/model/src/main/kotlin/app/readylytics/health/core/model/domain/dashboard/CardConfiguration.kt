package app.readylytics.health.core.model.domain.dashboard

import app.readylytics.health.core.model.domain.layout.ReorderableItem
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
    RAS_DAILY,
    CIRCADIAN_CONSISTENCY,
    RESTING_HR,
    RECOVERY_INDEX,
    ACUTE_CHRONIC_RATIO,
    SLEEP_EFFICIENCY,
    HEART_RATE,
    WEIGHT,
    BODY_FAT,
    BLOOD_PRESSURE,
    OXYGEN_SATURATION,
    AI_RECOMMENDATION,
    BODY_TEMPERATURE,
    INSIGHTS,
    RESIDUAL_FATIGUE,
    TRAINING_READINESS,
    CARDIO_FITNESS,
}

@Serializable
data class CardConfiguration(
    val cardId: CardId,
    override val isVisible: Boolean = true,
    override val position: Int = 0,
    @Serializable(with = NullableDashboardCardDisplayModeSerializer::class)
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
) : ReorderableItem<CardId> {
    override val id: CardId get() = cardId
}
