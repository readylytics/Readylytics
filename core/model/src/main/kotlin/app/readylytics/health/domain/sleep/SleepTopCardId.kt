package app.readylytics.health.domain.sleep

import kotlinx.serialization.Serializable

@Serializable
enum class SleepTopCardId {
    SLEEP_SCORE,
    SLEEP_DURATION_GAUGE,
    SLEEP_BREAKDOWN_BAR,
    SLEEP_STAGES_TIMELINE,
    SLEEP_HR_CHART,
}
