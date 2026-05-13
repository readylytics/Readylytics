package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardConfigurationRepository
    @Inject
    constructor(
        private val dataStore: DataStore<CardConfigurationsProto>,
    ) {
        private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun dashboardCardConfigurations(): Flow<List<CardConfiguration>> =
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        emit(CardConfigurationsSerializer.defaultValue)
                    } else {
                        throw exception
                    }
                }.map { proto ->
                    val stored = proto.dashboardCardsList.mapNotNull { CardConfigurationMapper.toDomain(it) }
                    val defaults = SettingsDefaults.DEFAULT_DASHBOARD_CARDS

                    // Merge: keep stored configs, but append any new default cards that aren't in stored yet
                    val storedIds = stored.map { it.cardId }.toSet()
                    val missingDefaults = defaults.filter { it.cardId !in storedIds }

                    if (missingDefaults.isEmpty()) {
                        stored
                    } else {
                        // Append missing defaults at the end with incremented positions
                        val maxPos = (stored.maxOfOrNull { it.position } ?: -1)
                        val appended =
                            missingDefaults.mapIndexed { index, config ->
                                config.copy(position = maxPos + 1 + index)
                            }
                        val merged = stored + appended

                        // Persist back to storage immediately so we don't re-calculate on next emission
                        repositoryScope.launch {
                            updateDashboardCardConfigurations(merged)
                        }

                        merged
                    }
                }

        suspend fun updateDashboardCardConfigurations(cards: List<CardConfiguration>) {
            dataStore.updateData { current ->
                val builder = current.toBuilder()
                val protoCards = cards.map { CardConfigurationMapper.toProto(it) }
                builder.clearDashboardCards().addAllDashboardCards(protoCards)
                builder.build()
            }
        }
    }
