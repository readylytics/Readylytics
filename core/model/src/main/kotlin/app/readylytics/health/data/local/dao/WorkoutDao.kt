package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.domain.model.TimestampedTrimp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout_records WHERE id = :id")
    suspend fun getById(id: String): WorkoutRecordEntity?

    @Query("SELECT * FROM workout_records WHERE startTime >= :fromMs ORDER BY startTime DESC")
    fun _observeSince(fromMs: Long): Flow<List<WorkoutRecordEntity>>

    fun observeSince(fromMs: Long): Flow<List<WorkoutRecordEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Query(
        "SELECT * FROM workout_records WHERE startTime >= :fromMs ORDER BY startTime ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun getPaged(
        fromMs: Long,
        limit: Int,
        offset: Int,
    ): List<WorkoutRecordEntity>

    @Query("SELECT * FROM workout_records WHERE startTime >= :fromMs ORDER BY startTime DESC")
    suspend fun getSince(fromMs: Long): List<WorkoutRecordEntity>

    @Query(
        "SELECT AVG(trimp) FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs",
    )
    suspend fun getAverageTrimp(
        fromMs: Long,
        toMs: Long,
    ): Float?

    @Query(
        "SELECT SUM(trimp) FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs",
    )
    suspend fun getTotalTrimp(
        fromMs: Long,
        toMs: Long,
    ): Float?

    @Query(
        // SCORE-001: prefer the user-selected-model TRIMP once a row has been touched by a
        // walk-forward recompute; fall back to the zone-weighted value for rows not yet backfilled.
        "SELECT startTime AS timestampMs, COALESCE(modelTrimp, trimp) AS trimp FROM workout_records " +
            "WHERE startTime >= :fromMs AND startTime < :toMs " +
            "AND COALESCE(modelTrimp, trimp) IS NOT NULL ORDER BY startTime ASC, id ASC",
    )
    suspend fun getTrimpPoints(
        fromMs: Long,
        toMs: Long,
    ): List<TimestampedTrimp>

    @Query("SELECT * FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs ORDER BY startTime ASC")
    suspend fun getWorkoutsInRange(
        fromMs: Long,
        toMs: Long,
    ): List<WorkoutRecordEntity>

    @Query(
        "SELECT * FROM workout_records WHERE endTime >= :fromMs AND startTime <= :toMs ORDER BY startTime ASC, id ASC",
    )
    suspend fun getOverlapping(
        fromMs: Long,
        toMs: Long,
    ): List<WorkoutRecordEntity>

    @Query("SELECT MIN(startTime) FROM workout_records")
    suspend fun getEarliestWorkoutTimestamp(): Long?

    @Query("SELECT SUM(durationMinutes) FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs")
    suspend fun getTotalDurationMinutes(
        fromMs: Long,
        toMs: Long,
    ): Int?

    @Query(
        "SELECT SUM(avgHr * durationMinutes) / CAST(SUM(durationMinutes) AS FLOAT) FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs AND durationMinutes > 0",
    )
    suspend fun getWeightedAvgHr(
        fromMs: Long,
        toMs: Long,
    ): Float?

    @Upsert
    suspend fun upsertAll(records: List<WorkoutRecordEntity>)

    @Query("DELETE FROM workout_records WHERE startTime < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM workout_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT COUNT(*) FROM workout_records")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM workout_records WHERE startTime >= :startMs AND startTime <= :endMs")
    suspend fun countInRange(startMs: Long, endMs: Long): Int

    @Query("DELETE FROM workout_records")
    suspend fun deleteAll(): Int

    @Query("SELECT DISTINCT deviceName FROM workout_records WHERE deviceName IS NOT NULL AND deviceName != ''")
    suspend fun getDistinctDeviceNames(): List<String>

    @Query(
        "DELETE FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs AND (deviceName != :deviceName OR deviceName IS NULL)",
    )
    suspend fun deleteRecordsNotMatchingDevice(
        fromMs: Long,
        toMs: Long,
        deviceName: String,
    ): Int
}
