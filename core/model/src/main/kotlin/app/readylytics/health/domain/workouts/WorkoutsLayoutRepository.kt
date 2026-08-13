package app.readylytics.health.domain.workouts

import app.readylytics.health.domain.dashboard.CardConfiguration
import kotlinx.coroutines.flow.Flow

interface WorkoutsLayoutRepository {
    fun workoutCardConfigurations(): Flow<List<CardConfiguration>>
    suspend fun updateWorkoutCardConfigurations(cards: List<CardConfiguration>)
    fun workoutChartConfigurations(): Flow<List<WorkoutChartConfiguration>>
    suspend fun updateWorkoutChartConfigurations(charts: List<WorkoutChartConfiguration>)
    fun workoutHistoryConfigurations(): Flow<List<WorkoutHistoryConfiguration>>
    suspend fun updateWorkoutHistoryConfigurations(history: List<WorkoutHistoryConfiguration>)
}
