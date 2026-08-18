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

    @Query("DELETE FROM workout_route_points WHERE workoutId IN (:workoutIds)")
    suspend fun deleteForWorkouts(workoutIds: List<String>): Int

    @Query("SELECT COUNT(*) FROM workout_route_points")
    suspend fun count(): Int

    /** Stable ordering by primary key so backup export can page without skipping rows. */
    @Query("SELECT * FROM workout_route_points ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun getPaged(
        limit: Int,
        offset: Int,
    ): List<WorkoutRoutePointEntity>

    @Query(
        "SELECT * FROM workout_route_points WHERE id > :afterId " +
            "ORDER BY id ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        afterId: Long,
        limit: Int,
    ): List<WorkoutRoutePointEntity>
}
