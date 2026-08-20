package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.di.ApplicationScope
import app.readylytics.health.domain.layout.LayoutDefaultsMerger
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SleepLayoutRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<SleepLayoutConfigurationsProto>,
        @param:ApplicationScope private val repositoryScope: CoroutineScope,
    ) : SleepLayoutRepository {
        init {
            repositoryScope.launch {
                ensureDefaultTopCardsArePresent()
                ensureDefaultChartsArePresent()
                ensureDefaultMetricCardsArePresent()
            }
        }

        private suspend fun ensureDefaultTopCardsArePresent() {
            dataStore.updateData { proto ->
                val stored = proto.topCardsList.mapNotNull { SleepLayoutMapper.toTopCardDomain(it) }
                val merged =
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                if (merged === stored) {
                    proto
                } else {
                    proto
                        .toBuilder()
                        .clearTopCards()
                        .addAllTopCards(merged.map { SleepLayoutMapper.toTopCardProto(it) })
                        .build()
                }
            }
        }

        private suspend fun ensureDefaultChartsArePresent() {
            dataStore.updateData { proto ->
                val stored = proto.trendChartsList.mapNotNull { SleepLayoutMapper.toChartDomain(it) }
                val merged =
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_SLEEP_CHARTS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                if (merged === stored) {
                    proto
                } else {
                    proto
                        .toBuilder()
                        .clearTrendCharts()
                        .addAllTrendCharts(merged.map { SleepLayoutMapper.toChartProto(it) })
                        .build()
                }
            }
        }

        private suspend fun ensureDefaultMetricCardsArePresent() {
            dataStore.updateData { proto ->
                val stored = proto.metricCardsList.mapNotNull { SleepLayoutMapper.toMetricCardDomain(it) }
                val merged =
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                if (merged === stored) {
                    proto
                } else {
                    proto
                        .toBuilder()
                        .clearMetricCards()
                        .addAllMetricCards(merged.map { SleepLayoutMapper.toMetricCardProto(it) })
                        .build()
                }
            }
        }

        override fun sleepTopCardConfigurations(): Flow<List<SleepTopCardConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(SleepLayoutConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.topCardsList.mapNotNull { SleepLayoutMapper.toTopCardDomain(it) }
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                }

        override fun sleepChartConfigurations(): Flow<List<SleepChartConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(SleepLayoutConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.trendChartsList.mapNotNull { SleepLayoutMapper.toChartDomain(it) }
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_SLEEP_CHARTS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                }

        override fun sleepMetricCardConfigurations(): Flow<List<SleepMetricCardConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(SleepLayoutConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.metricCardsList.mapNotNull { SleepLayoutMapper.toMetricCardDomain(it) }
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                }

        override suspend fun updateSleepTopCardConfigurations(cards: List<SleepTopCardConfiguration>) {
            dataStore.updateData { current ->
                val builder = current.toBuilder()
                val protoCards = cards.map { SleepLayoutMapper.toTopCardProto(it) }
                builder.clearTopCards().addAllTopCards(protoCards)
                builder.build()
            }
        }

        override suspend fun updateSleepChartConfigurations(charts: List<SleepChartConfiguration>) {
            dataStore.updateData { current ->
                val builder = current.toBuilder()
                val protoCharts = charts.map { SleepLayoutMapper.toChartProto(it) }
                builder.clearTrendCharts().addAllTrendCharts(protoCharts)
                builder.build()
            }
        }

        override suspend fun updateSleepMetricCardConfigurations(cards: List<SleepMetricCardConfiguration>) {
            dataStore.updateData { current ->
                val builder = current.toBuilder()
                val protoCards = cards.map { SleepLayoutMapper.toMetricCardProto(it) }
                builder.clearMetricCards().addAllMetricCards(protoCards)
                builder.build()
            }
        }
    }
