package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

data class SleepHrSample(
    val sessionId: String,
    val beatsPerMinute: Int,
)

@Dao
interface HeartRateDao {
    @Query(
        "SELECT * FROM heart_rate_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC, id ASC",
    )
    fun _observeSleepHrSince(fromMs: Long): Flow<List<HeartRateRecordEntity>>

    fun observeSleepHrSince(fromMs: Long): Flow<List<HeartRateRecordEntity>> =
        _observeSleepHrSince(fromMs).distinctUntilChanged()

    @Query("SELECT * FROM heart_rate_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, id ASC")
    suspend fun getSince(fromMs: Long): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, id ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun getPaged(
        fromMs: Long,
        limit: Int,
        offset: Int,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId",
    )
    suspend fun getAvgSleepHr(sessionId: String): Int?

    @Query(
        "SELECT sessionId, CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) AS avgHr FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId IN (:sessionIds) " +
            "GROUP BY sessionId",
    )
    suspend fun getAvgSleepHrForSessions(
        sessionIds: List<String>,
    ): Map<
        @MapColumn(columnName = "sessionId")
        String,
        @MapColumn(columnName = "avgHr")
        Int,
    >

    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId IS NOT NULL AND timestampMs >= :fromMs " +
            "GROUP BY sessionId",
    )
    suspend fun getAvgSleepHrPerSession(fromMs: Long): List<Int>

    @Query(
        "SELECT beatsPerMinute FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, id ASC",
    )
    suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int>

    @Query(
        "SELECT COUNT(*) FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP'",
    )
    suspend fun getSleepHrSampleCount(sessionId: String): Int

    @Query(
        "SELECT beatsPerMinute FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, id ASC LIMIT 1 OFFSET :offset",
    )
    suspend fun getSleepHrSampleAtOffset(
        sessionId: String,
        offset: Int,
    ): Int?

    @Query(
        "SELECT MIN(beatsPerMinute) FROM heart_rate_records " +
            "WHERE timestampMs >= :startTimeMs AND timestampMs <= :endTimeMs",
    )
    suspend fun getMinHrInRange(
        startTimeMs: Long,
        endTimeMs: Long,
    ): Int?

    @Query(
        "SELECT timestampMs FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, id ASC LIMIT 1",
    )
    suspend fun getMinHrTimestamp(sessionId: String): Long?

    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC, id ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<HeartRateRecordEntity>

    @Query(
        "SELECT * FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs < :endMs " +
            "ORDER BY timestampMs ASC, id ASC",
    )
    fun _observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordEntity>>

    fun observeByTimeRange(
        startMs: Long,
        endMs: Long,
    ): Flow<List<HeartRateRecordEntity>> = _observeByTimeRange(startMs, endMs).distinctUntilChanged()

    @Upsert
    suspend fun upsertAll(records: List<HeartRateRecordEntity>)

    @Query("DELETE FROM heart_rate_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM heart_rate_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM heart_rate_records WHERE id = :id")
    suspend fun getById(id: String): HeartRateRecordEntity?

    @Query(
        "SELECT * FROM heart_rate_records " +
            "WHERE id = :sourceRecordId " +
            "OR substr(id, 1, length(:sourceRecordId) + 1) = :sourceRecordId || '_' " +
            "ORDER BY timestampMs ASC, id ASC",
    )
    suspend fun getBySourceRecordId(sourceRecordId: String): List<HeartRateRecordEntity>

    @Query(
        "DELETE FROM heart_rate_records " +
            "WHERE id = :sourceRecordId " +
            "OR substr(id, 1, length(:sourceRecordId) + 1) = :sourceRecordId || '_'",
    )
    suspend fun deleteBySourceRecordId(sourceRecordId: String): Int

    @Query("SELECT COUNT(*) FROM heart_rate_records")
    suspend fun count(): Int

    @Query("DELETE FROM heart_rate_records")
    suspend fun deleteAll(): Int

    @Query("SELECT DISTINCT deviceName FROM heart_rate_records WHERE deviceName IS NOT NULL AND deviceName != ''")
    suspend fun getDistinctDeviceNames(): List<String>

    @Query(
        "DELETE FROM heart_rate_records WHERE timestampMs >= :fromMs AND timestampMs < :toMs AND (deviceName != :deviceName OR deviceName IS NULL)",
    )
    suspend fun deleteRecordsNotMatchingDevice(
        fromMs: Long,
        toMs: Long,
        deviceName: String,
    ): Int

    /**
     * Batch fetch all sleep HR samples for multiple sessions in a single query.
     * Used to fix N+1 query pattern in baseline computation.
     *
     * Returns all records with their sessionId so they can be grouped in memory.
     * More efficient than per-session queries for computing statistics.
     */
    @Query(
        "SELECT id, sessionId, recordType, beatsPerMinute, timestampMs, deviceName " +
            "FROM heart_rate_records " +
            "WHERE sessionId IN (:sessionIds) AND recordType = 'SLEEP' " +
            "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, id ASC",
    )
    suspend fun getSleepHrSamplesForSessions(sessionIds: List<String>): List<HeartRateRecordEntity>

    @Query(
        "SELECT sessionId, beatsPerMinute " +
            "FROM heart_rate_records " +
            "WHERE sessionId IN (:sessionIds) AND recordType = 'SLEEP' " +
            "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, id ASC",
    )
    suspend fun getSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample>

    @Query("SELECT MIN(timestampMs) FROM heart_rate_records")
    fun observeEarliestHrTime(): Flow<Long?>
}
