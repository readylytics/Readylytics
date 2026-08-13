package app.readylytics.health.domain.sleep

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.NullableDashboardCardDisplayModeSerializer
import app.readylytics.health.domain.layout.ReorderableItem
import kotlinx.serialization.Serializable

@Serializable
data class SleepMetricCardConfiguration(
    val cardId: SleepMetricCardId,
    override val isVisible: Boolean = true,
    override val position: Int = 0,
    @Serializable(with = NullableDashboardCardDisplayModeSerializer::class)
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
) : ReorderableItem<SleepMetricCardId> {
    override val id: SleepMetricCardId get() = cardId
}
