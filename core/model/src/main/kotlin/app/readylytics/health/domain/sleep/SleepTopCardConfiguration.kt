package app.readylytics.health.domain.sleep

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.NullableDashboardCardDisplayModeSerializer
import kotlinx.serialization.Serializable

@Serializable
data class SleepTopCardConfiguration(
    val cardId: SleepTopCardId,
    val isVisible: Boolean = true,
    val position: Int = 0,
    @Serializable(with = NullableDashboardCardDisplayModeSerializer::class)
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
)
