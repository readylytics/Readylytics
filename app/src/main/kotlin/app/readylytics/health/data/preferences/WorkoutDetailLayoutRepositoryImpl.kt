package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.layout.LayoutDefaultsMerger
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.domain.workouts.detail.WorkoutLayoutType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutDetailLayoutRepositoryImpl
    @Inject
    constructor(
        private val dataStore: DataStore<WorkoutDetailLayoutConfigurationsProto>,
    ) : WorkoutDetailLayoutRepository {
        private fun protoFlow(): Flow<WorkoutDetailLayoutConfigurationsProto> =
            dataStore.data.catch { exception ->
                if (exception is IOException) {
                    emit(WorkoutDetailLayoutConfigurationsSerializer.defaultValue)
                } else {
                    throw exception
                }
            }

        override fun layoutFor(type: WorkoutLayoutType): Flow<List<WorkoutDetailItemConfiguration>> =
            protoFlow().map { proto ->
                val stored =
                    proto.layoutsByTypeMap[type.name]
                        ?.itemsList
                        ?.mapNotNull { WorkoutDetailLayoutMapper.toDomain(it) }
                        ?: return@map SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS
                LayoutDefaultsMerger.mergeWithDefaults(
                    stored = stored,
                    defaults = SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS,
                    withPosition = { config, pos -> config.copy(position = pos) },
                )
            }

        override fun allLayouts(): Flow<Map<WorkoutLayoutType, List<WorkoutDetailItemConfiguration>>> =
            protoFlow().map { proto ->
                proto.layoutsByTypeMap
                    .mapNotNull { (key, layout) ->
                        val type = WorkoutDetailLayoutMapper.typeFromKey(key) ?: return@mapNotNull null
                        type to layout.itemsList.mapNotNull { WorkoutDetailLayoutMapper.toDomain(it) }
                    }.toMap()
            }

        override suspend fun updateLayout(
            type: WorkoutLayoutType,
            items: List<WorkoutDetailItemConfiguration>,
        ) {
            dataStore.updateData { current ->
                current
                    .toBuilder()
                    .putLayoutsByType(type.name, WorkoutDetailLayoutMapper.toLayoutProto(items))
                    .build()
            }
        }

        override suspend fun replaceAll(layouts: Map<WorkoutLayoutType, List<WorkoutDetailItemConfiguration>>) {
            dataStore.updateData { current ->
                current
                    .toBuilder()
                    .clearLayoutsByType()
                    .putAllLayoutsByType(
                        layouts.entries.associate { (type, items) ->
                            type.name to WorkoutDetailLayoutMapper.toLayoutProto(items)
                        },
                    ).build()
            }
        }

        override suspend fun resetAll() {
            dataStore.updateData { current -> current.toBuilder().clearLayoutsByType().build() }
        }
    }
