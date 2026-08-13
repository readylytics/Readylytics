package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.di.ApplicationScope
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.workouts.WorkoutChartConfiguration
import app.readylytics.health.domain.workouts.WorkoutHistoryConfiguration
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutsLayoutRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<WorkoutsLayoutConfigurationsProto>,
        @param:ApplicationScope private val repositoryScope: CoroutineScope,
    ) : WorkoutsLayoutRepository {
        init {
            repositoryScope.launch {
                ensureDefaultCardsArePresent()
                ensureDefaultChartsArePresent()
                ensureDefaultHistoryArePresent()
            }
        }

        private suspend fun ensureDefaultCardsArePresent() {
            dataStore.updateData { proto ->
                val stored = proto.workoutCardsList.mapNotNull { WorkoutsLayoutMapper.toCardDomain(it) }
                val defaults = SettingsDefaults.DEFAULT_WORKOUT_CARDS
                val storedIds = stored.map { it.cardId }.toSet()
                val missingDefaults = defaults.filter { it.cardId !in storedIds }

                if (missingDefaults.isEmpty()) {
                    proto
                } else {
                    val maxPos = (stored.maxOfOrNull { it.position } ?: -1)
                    val appended =
                        missingDefaults.mapIndexed { index, config ->
                            config.copy(
                                position =
                                    maxPos + 1 + index,
                            )
                        }
                    val merged = stored + appended
                    proto
                        .toBuilder()
                        .clearWorkoutCards()
                        .addAllWorkoutCards(merged.map { WorkoutsLayoutMapper.toCardProto(it) })
                        .build()
                }
            }
        }

        private suspend fun ensureDefaultChartsArePresent() {
            dataStore.updateData { proto ->
                val stored = proto.workoutChartsList.mapNotNull { WorkoutsLayoutMapper.toChartDomain(it) }
                val defaults = SettingsDefaults.DEFAULT_WORKOUT_CHARTS
                val storedIds = stored.map { it.chartId }.toSet()
                val missingDefaults = defaults.filter { it.chartId !in storedIds }

                if (missingDefaults.isEmpty()) {
                    proto
                } else {
                    val maxPos = (stored.maxOfOrNull { it.position } ?: -1)
                    val appended =
                        missingDefaults.mapIndexed { index, config ->
                            config.copy(
                                position =
                                    maxPos + 1 + index,
                            )
                        }
                    val merged = stored + appended
                    proto
                        .toBuilder()
                        .clearWorkoutCharts()
                        .addAllWorkoutCharts(merged.map { WorkoutsLayoutMapper.toChartProto(it) })
                        .build()
                }
            }
        }

        private suspend fun ensureDefaultHistoryArePresent() {
            dataStore.updateData { proto ->
                val stored = proto.workoutHistoryList.mapNotNull { WorkoutsLayoutMapper.toHistoryDomain(it) }
                val defaults = SettingsDefaults.DEFAULT_WORKOUT_HISTORY
                val storedIds = stored.map { it.historyId }.toSet()
                val missingDefaults = defaults.filter { it.historyId !in storedIds }

                if (missingDefaults.isEmpty()) {
                    proto
                } else {
                    val maxPos = (stored.maxOfOrNull { it.position } ?: -1)
                    val appended =
                        missingDefaults.mapIndexed { index, config ->
                            config.copy(
                                position =
                                    maxPos + 1 + index,
                            )
                        }
                    val merged = stored + appended
                    proto
                        .toBuilder()
                        .clearWorkoutHistory()
                        .addAllWorkoutHistory(merged.map { WorkoutsLayoutMapper.toHistoryProto(it) })
                        .build()
                }
            }
        }

        override fun workoutCardConfigurations(): Flow<List<CardConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(WorkoutsLayoutConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.workoutCardsList.mapNotNull { WorkoutsLayoutMapper.toCardDomain(it) }
                    val defaults = SettingsDefaults.DEFAULT_WORKOUT_CARDS
                    val storedIds = stored.map { it.cardId }.toSet()
                    val missingDefaults = defaults.filter { it.cardId !in storedIds }
                    if (missingDefaults.isEmpty()) {
                        stored
                    } else {
                        val maxPos = (stored.maxOfOrNull { it.position } ?: -1)
                        stored +
                            missingDefaults.mapIndexed { index, config -> config.copy(position = maxPos + 1 + index) }
                    }
                }

        override fun workoutChartConfigurations(): Flow<List<WorkoutChartConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(WorkoutsLayoutConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.workoutChartsList.mapNotNull { WorkoutsLayoutMapper.toChartDomain(it) }
                    val defaults = SettingsDefaults.DEFAULT_WORKOUT_CHARTS
                    val storedIds = stored.map { it.chartId }.toSet()
                    val missingDefaults = defaults.filter { it.chartId !in storedIds }
                    if (missingDefaults.isEmpty()) {
                        stored
                    } else {
                        val maxPos = (stored.maxOfOrNull { it.position } ?: -1)
                        stored +
                            missingDefaults.mapIndexed { index, config -> config.copy(position = maxPos + 1 + index) }
                    }
                }

        override fun workoutHistoryConfigurations(): Flow<List<WorkoutHistoryConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(WorkoutsLayoutConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.workoutHistoryList.mapNotNull { WorkoutsLayoutMapper.toHistoryDomain(it) }
                    val defaults = SettingsDefaults.DEFAULT_WORKOUT_HISTORY
                    val storedIds = stored.map { it.historyId }.toSet()
                    val missingDefaults = defaults.filter { it.historyId !in storedIds }
                    if (missingDefaults.isEmpty()) {
                        stored
                    } else {
                        val maxPos = (stored.maxOfOrNull { it.position } ?: -1)
                        stored +
                            missingDefaults.mapIndexed { index, config -> config.copy(position = maxPos + 1 + index) }
                    }
                }

        override suspend fun updateWorkoutCardConfigurations(cards: List<CardConfiguration>) {
            dataStore.updateData { current ->
                current
                    .toBuilder()
                    .clearWorkoutCards()
                    .addAllWorkoutCards(cards.map { WorkoutsLayoutMapper.toCardProto(it) })
                    .build()
            }
        }

        override suspend fun updateWorkoutChartConfigurations(charts: List<WorkoutChartConfiguration>) {
            dataStore.updateData { current ->
                current
                    .toBuilder()
                    .clearWorkoutCharts()
                    .addAllWorkoutCharts(charts.map { WorkoutsLayoutMapper.toChartProto(it) })
                    .build()
            }
        }

        override suspend fun updateWorkoutHistoryConfigurations(history: List<WorkoutHistoryConfiguration>) {
            dataStore.updateData { current ->
                current
                    .toBuilder()
                    .clearWorkoutHistory()
                    .addAllWorkoutHistory(history.map { WorkoutsLayoutMapper.toHistoryProto(it) })
                    .build()
            }
        }
    }
