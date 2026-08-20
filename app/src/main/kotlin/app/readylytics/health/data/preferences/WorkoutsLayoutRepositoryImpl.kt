package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.di.ApplicationScope
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.layout.LayoutDefaultsMerger
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
                val merged =
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_WORKOUT_CARDS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                if (merged === stored) {
                    proto
                } else {
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
                val merged =
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_WORKOUT_CHARTS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                if (merged === stored) {
                    proto
                } else {
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
                val merged =
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_WORKOUT_HISTORY,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                if (merged === stored) {
                    proto
                } else {
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
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_WORKOUT_CARDS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
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
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_WORKOUT_CHARTS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
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
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_WORKOUT_HISTORY,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
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
