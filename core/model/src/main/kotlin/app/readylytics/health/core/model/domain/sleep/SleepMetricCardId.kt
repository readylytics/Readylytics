package app.readylytics.health.core.model.domain.sleep

import kotlinx.serialization.Serializable

@Serializable
enum class SleepMetricCardId {
    CIRCADIAN_CONSISTENCY,
    SLEEP_EFFICIENCY,
    DEEP_SLEEP,
    REM_SLEEP,
    NAP_DURATION,
    NAP_COUNT,
}
