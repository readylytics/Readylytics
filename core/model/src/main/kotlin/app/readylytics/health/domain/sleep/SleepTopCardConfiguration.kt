package app.readylytics.health.domain.sleep

import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.core.model.domain.dashboard.NullableDashboardCardDisplayModeSerializer
import app.readylytics.health.domain.layout.ReorderableItem
import kotlinx.serialization.Serializable

@Serializable
data class SleepTopCardConfiguration(
    val cardId: SleepTopCardId,
    override val isVisible: Boolean = true,
    override val position: Int = 0,
    @Serializable(with = NullableDashboardCardDisplayModeSerializer::class)
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
) : ReorderableItem<SleepTopCardId> {
    override val id: SleepTopCardId get() = cardId
}
