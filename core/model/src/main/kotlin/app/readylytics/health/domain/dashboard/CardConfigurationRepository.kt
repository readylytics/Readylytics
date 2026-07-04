package app.readylytics.health.domain.dashboard

import kotlinx.coroutines.flow.Flow

interface CardConfigurationRepository {
    fun dashboardCardConfigurations(): Flow<List<CardConfiguration>>
    suspend fun updateDashboardCardConfigurations(cards: List<CardConfiguration>)
}
