package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode

interface DashboardCardActions {
    val cardActionHandler: DashboardCardActionHandler

    fun toggleCardManagement() = cardActionHandler.toggleCardManagement()

    fun onCancelCardManagement() = cardActionHandler.onCancelCardManagement()

    fun onToggleCardVisibility(
        cardId: CardId,
        visible: Boolean,
    ) = cardActionHandler.onToggleCardVisibility(cardId, visible)

    fun onReorderCards(newOrder: List<CardConfiguration>) = cardActionHandler.onReorderCards(newOrder)

    fun onResetToDefaults() = cardActionHandler.onResetToDefaults()

    fun onCardDisplayModeChanged(
        cardId: CardId,
        mode: DashboardCardDisplayMode,
    ) = cardActionHandler.onCardDisplayModeChanged(cardId, mode)
}
