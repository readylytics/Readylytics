package app.readylytics.health.core.model.domain.vitals

import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import kotlinx.coroutines.flow.Flow

interface VitalsLayoutRepository {
    fun vitalsCardConfigurations(): Flow<List<CardConfiguration>>
    suspend fun updateVitalsCardConfigurations(cards: List<CardConfiguration>)
    fun vitalsChartConfigurations(): Flow<List<VitalsChartConfiguration>>
    suspend fun updateVitalsChartConfigurations(charts: List<VitalsChartConfiguration>)
}