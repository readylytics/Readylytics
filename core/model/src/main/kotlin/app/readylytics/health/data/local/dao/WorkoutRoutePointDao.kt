package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.data.local.entity.WorkoutRoutePointEntity

@Dao
interface WorkoutRoutePointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<WorkoutRoutePointEntity>)

    @Query("SELECT * FROM workout_route_points WHERE workoutId = :workoutId ORDER BY timestampMs ASC")
    suspend fun getRoutePoints(workoutId: String): List<WorkoutRoutePointEntity>

    @Query("DELETE FROM workout_route_points WHERE workoutId = :workoutId")
    suspend fun deleteByWorkoutId(workoutId: String): Int
}
