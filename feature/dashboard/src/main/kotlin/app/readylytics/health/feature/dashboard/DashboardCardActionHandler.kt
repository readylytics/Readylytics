package app.readylytics.health.feature.dashboard

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.dashboard.CardId
import app.readylytics.health.core.model.domain.dashboard.CardManagementDelegate
import app.readylytics.health.core.model.domain.dashboard.CardManagementEvent
import app.readylytics.health.core.model.domain.dashboard.DashboardCardDisplayMode

class DashboardCardActionHandler(
    private val cardManagementDelegate: CardManagementDelegate,
    private val currentCardConfigurations: () -> List<CardConfiguration>,
) {
    fun toggleCardManagement() {
        if (cardManagementDelegate.isManagingCards.value) {
            cardManagementDelegate.saveChanges()
        } else {
            cardManagementDelegate.enterEditMode(currentCardConfigurations())
        }
    }

    fun onCancelCardManagement() {
        cardManagementDelegate.cancelChanges()
    }

    fun onToggleCardVisibility(
        cardId: CardId,
        visible: Boolean,
    ) {
        cardManagementDelegate.onToggleCardVisibility(
            currentCardConfigurations(),
            cardId,
            visible,
        )
    }

    fun onReorderCards(newOrder: List<CardConfiguration>) {
        cardManagementDelegate.onReorderCards(
            currentCardConfigurations(),
            newOrder,
        )
    }

    fun onResetToDefaults() {
        cardManagementDelegate.onResetToDefaults()
    }

    fun onCardDisplayModeChanged(
        cardId: CardId,
        mode: DashboardCardDisplayMode,
    ) {
        cardManagementDelegate.onEvent(CardManagementEvent.DisplayModeChanged(cardId, mode))
    }
}
