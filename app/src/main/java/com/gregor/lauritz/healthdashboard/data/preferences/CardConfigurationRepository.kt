package com.gregor.lauritz.healthdashboard.data.preferences

import androidx.datastore.core.DataStore
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.ScreenType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CardConfigurationRepository @Inject constructor(
    private val dataStore: DataStore<CardConfigurationsProto>,
) {
    fun dashboardCardConfigurations(): Flow<List<CardConfiguration>> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(CardConfigurationsSerializer.defaultValue)
                } else {
                    throw exception
                }
            }
            .map { proto ->
                proto.dashboardCardsList.map { CardConfigurationMapper.toDomain(it) }
            }

    fun sleepCardConfigurations(): Flow<List<CardConfiguration>> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(CardConfigurationsSerializer.defaultValue)
                } else {
                    throw exception
                }
            }
            .map { proto ->
                proto.sleepCardsList.map { CardConfigurationMapper.toDomain(it) }
            }

    fun workoutCardConfigurations(): Flow<List<CardConfiguration>> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(CardConfigurationsSerializer.defaultValue)
                } else {
                    throw exception
                }
            }
            .map { proto ->
                proto.workoutCardsList.map { CardConfigurationMapper.toDomain(it) }
            }

    suspend fun updateCardConfigurations(
        screenType: ScreenType,
        cards: List<CardConfiguration>,
    ) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val protoCards = cards.map { CardConfigurationMapper.toProto(it) }
            when (screenType) {
                ScreenType.DASHBOARD -> builder.clearDashboardCards().addAllDashboardCards(protoCards)
                ScreenType.SLEEP -> builder.clearSleepCards().addAllSleepCards(protoCards)
                ScreenType.WORKOUTS -> builder.clearWorkoutCards().addAllWorkoutCards(protoCards)
            }
            builder.build()
        }
    }
}
