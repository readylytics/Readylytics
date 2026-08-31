package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.di.ApplicationScope
import app.readylytics.health.core.model.domain.dashboard.CardConfiguration
import app.readylytics.health.core.model.domain.layout.LayoutDefaultsMerger
import app.readylytics.health.core.model.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.core.model.domain.vitals.VitalsLayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VitalsLayoutRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<VitalsLayoutConfigurationsProto>,
        @param:ApplicationScope private val repositoryScope: CoroutineScope,
    ) : VitalsLayoutRepository {
        init {
            repositoryScope.launch {
                ensureDefaultCardsArePresent()
                ensureDefaultChartsArePresent()
            }
        }

        private suspend fun ensureDefaultCardsArePresent() {
            dataStore.updateData { proto ->
                val stored = proto.vitalsCardsList.mapNotNull { VitalsLayoutMapper.toCardDomain(it) }
                val merged =
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_VITALS_CARDS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                if (merged === stored) {
                    proto
                } else {
                    proto
                        .toBuilder()
                        .clearVitalsCards()
                        .addAllVitalsCards(merged.map { VitalsLayoutMapper.toCardProto(it) })
                        .build()
                }
            }
        }

        private suspend fun ensureDefaultChartsArePresent() {
            dataStore.updateData { proto ->
                val stored = proto.trendChartsList.mapNotNull { VitalsLayoutMapper.toChartDomain(it) }
                val merged =
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_VITALS_CHARTS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                if (merged === stored) {
                    proto
                } else {
                    proto
                        .toBuilder()
                        .clearTrendCharts()
                        .addAllTrendCharts(merged.map { VitalsLayoutMapper.toChartProto(it) })
                        .build()
                }
            }
        }

        override fun vitalsCardConfigurations(): Flow<List<CardConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(VitalsLayoutConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.vitalsCardsList.mapNotNull { VitalsLayoutMapper.toCardDomain(it) }
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_VITALS_CARDS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                }

        override fun vitalsChartConfigurations(): Flow<List<VitalsChartConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(VitalsLayoutConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.trendChartsList.mapNotNull { VitalsLayoutMapper.toChartDomain(it) }
                    LayoutDefaultsMerger.mergeWithDefaults(
                        stored = stored,
                        defaults = SettingsDefaults.DEFAULT_VITALS_CHARTS,
                        withPosition = { config, pos -> config.copy(position = pos) },
                    )
                }

        override suspend fun updateVitalsCardConfigurations(cards: List<CardConfiguration>) {
            dataStore.updateData { current ->
                val builder = current.toBuilder()
                val protoCards = cards.map { VitalsLayoutMapper.toCardProto(it) }
                builder.clearVitalsCards().addAllVitalsCards(protoCards)
                builder.build()
            }
        }

        override suspend fun updateVitalsChartConfigurations(charts: List<VitalsChartConfiguration>) {
            dataStore.updateData { current ->
                val builder = current.toBuilder()
                val protoCharts = charts.map { VitalsLayoutMapper.toChartProto(it) }
                builder.clearTrendCharts().addAllTrendCharts(protoCharts)
                builder.build()
            }
        }
    }
