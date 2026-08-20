package app.readylytics.health.core.model.domain.sleep

import kotlinx.coroutines.flow.Flow

interface SleepLayoutRepository {
    fun sleepTopCardConfigurations(): Flow<List<SleepTopCardConfiguration>>
    suspend fun updateSleepTopCardConfigurations(cards: List<SleepTopCardConfiguration>)

    fun sleepChartConfigurations(): Flow<List<SleepChartConfiguration>>
    suspend fun updateSleepChartConfigurations(charts: List<SleepChartConfiguration>)

    fun sleepMetricCardConfigurations(): Flow<List<SleepMetricCardConfiguration>>
    suspend fun updateSleepMetricCardConfigurations(cards: List<SleepMetricCardConfiguration>)
}
