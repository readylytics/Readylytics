package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.model.TimestampedTrimp
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout_records WHERE id = :id")
    suspend fun getById(id: String): WorkoutRecordEntity?

    @Query("SELECT * FROM workout_records WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<WorkoutRecordEntity>

    @Query("SELECT modelTrimp FROM workout_records WHERE id = :id")
    suspend fun getModelTrimpById(id: String): Float?

    @Query("SELECT * FROM workout_records WHERE startTime >= :fromMs ORDER BY startTime DESC")
    fun _observeSince(fromMs: Long): Flow<List<WorkoutRecordEntity>>

    fun observeSince(fromMs: Long): Flow<List<WorkoutRecordEntity>> = _observeSince(fromMs).distinctUntilChanged()


    @Query(
        "SELECT * FROM workout_records " +
            "WHERE startTime >= :fromMs AND (" +
            "  startTime > :afterTs OR " +
            "  (startTime = :afterTs AND id > :afterId)" +
            ") " +
            "ORDER BY startTime ASC, id ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterId: String,
        limit: Int,
    ): List<WorkoutRecordEntity>

    @Query(
        "SELECT * FROM workout_records " +
            "WHERE startTime >= :fromMs AND startTime < :toMs AND (" +
            "  startTime < :beforeTs OR " +
            "  (startTime = :beforeTs AND id < :beforeId)" +
            ") " +
            "ORDER BY startTime DESC, id DESC " +
            "LIMIT :limit",
    )
    suspend fun pageAfterInRange(
        fromMs: Long,
        toMs: Long,
        beforeTs: Long,
        beforeId: String,
        limit: Int,
    ): List<WorkoutRecordEntity>

    @Query(
        "SELECT * FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs " +
            "ORDER BY startTime DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun getPagedInRange(
        fromMs: Long,
        toMs: Long,
        limit: Int,
        offset: Int,
    ): List<WorkoutRecordEntity>

    @Query(
        "SELECT COUNT(*) FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs",
    )
    suspend fun countByTimeRange(fromMs: Long, toMs: Long): Int

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
        "SELECT id AS workoutId, endTime AS endTimeMs, modelTrimp AS trimp FROM workout_records " +
            "WHERE endTime <= :evaluationTimeMs AND modelTrimp IS NOT NULL AND modelTrimp > 0 " +
            "ORDER BY endTime ASC, id ASC",
    )
    suspend fun getCanonicalFatigueInputsThrough(evaluationTimeMs: Long): List<FatigueWorkoutInput>

    @Query(
        "SELECT id AS workoutId, endTime AS endTimeMs, modelTrimp AS trimp FROM workout_records " +
            "WHERE startTime < :startBeforeMs AND modelTrimp IS NOT NULL AND modelTrimp > 0 " +
            "ORDER BY endTime ASC, id ASC",
    )
    suspend fun getCanonicalFatigueSeed(startBeforeMs: Long): List<FatigueWorkoutInput>

    @Query(
        // Retention-bounded, exactly like countUnbackfilledSince. This gate must never reach further
        // back than the rows the startup self-heal can repair: a single null-modelTrimp row older
        // than the retention start would otherwise pin residual fatigue to null forever, because no
        // recompute will ever visit it to clear the count.
        "SELECT COUNT(*) FROM workout_records " +
            "WHERE startTime >= :retentionStartMs AND startTime < :startBeforeMs AND modelTrimp IS NULL",
    )
    suspend fun countUnbackfilledBefore(
        retentionStartMs: Long,
        startBeforeMs: Long,
    ): Int

    @Query(
        // Retention-bounded for the same reason as countUnbackfilledBefore; this is the
        // single-day fallback's gate.
        "SELECT COUNT(*) FROM workout_records " +
            // The lower bound filters startTime, matching countUnbackfilledSince exactly, so a
            // workout straddling the retention boundary cannot be counted here while being
            // invisible to the self-heal that would repair it.
            "WHERE startTime >= :retentionStartMs AND endTime <= :evaluationTimeMs AND modelTrimp IS NULL",
    )
    suspend fun countUnbackfilledThrough(
        retentionStartMs: Long,
        evaluationTimeMs: Long,
    ): Int

    @Query(
        // Retention-bounded self-heal gate: rows older than the retention start can never be
        // reached by the recompute-only resync, so counting them would re-enqueue on every launch.
        "SELECT COUNT(*) FROM workout_records " +
            "WHERE startTime >= :retentionStartMs AND modelTrimp IS NULL",
    )
    suspend fun countUnbackfilledSince(retentionStartMs: Long): Int

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
        "SELECT SUM(avgHr * durationMinutes) / CAST(SUM(durationMinutes) AS FLOAT) " +
            "FROM workout_records WHERE startTime >= :fromMs AND startTime < :toMs AND durationMinutes > 0",
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
        "DELETE FROM workout_records " +
            "WHERE startTime >= :fromMs AND startTime < :toMs AND (deviceName != :deviceName OR deviceName IS NULL)",
    )
    suspend fun deleteRecordsNotMatchingDevice(
        fromMs: Long,
        toMs: Long,
        deviceName: String,
    ): Int

    @Query("SELECT * FROM workout_records WHERE startTime >= :startMs AND endTime <= :endMs ORDER BY startTime ASC")
    suspend fun getBetween(startMs: Long, endMs: Long): List<WorkoutRecordEntity>

    @Query("DELETE FROM workout_records WHERE startTime >= :startMs AND endTime <= :endMs AND id NOT IN (:validIds)")
    suspend fun deleteWorkoutsNotIn(startMs: Long, endMs: Long, validIds: List<String>): Int

    @Query("DELETE FROM workout_records WHERE startTime >= :startMs AND endTime <= :endMs")
    suspend fun deleteBetween(startMs: Long, endMs: Long): Int
}
