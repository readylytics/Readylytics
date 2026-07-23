package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.readylytics.health.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface HrvDao {
    @Query("SELECT * FROM hrv_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordId ASC")
    fun _observeSince(fromMs: Long): Flow<List<HrvRecordEntity>>

    fun observeSince(fromMs: Long): Flow<List<HrvRecordEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Query(
        "SELECT * FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    fun _observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordEntity>>

    fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordEntity>> =
        _observeSleepHrvSince(fromMs).distinctUntilChanged()

    @Query("SELECT * FROM hrv_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordId ASC")
    suspend fun getSince(fromMs: Long): List<HrvRecordEntity>

    @Query(
        "SELECT * FROM hrv_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordId ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun getPaged(
        fromMs: Long,
        limit: Int,
        offset: Int,
    ): List<HrvRecordEntity>

    @Query(
        "SELECT * FROM hrv_records " +
            "WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "AND (timestampMs > :lastTimestampMs OR (timestampMs = :lastTimestampMs AND sourceRecordId > :lastId)) " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC LIMIT :limit",
    )
    suspend fun getKeysetPage(
        startMs: Long,
        endMs: Long,
        lastTimestampMs: Long,
        lastId: String,
        limit: Int,
    ): List<HrvRecordEntity>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getSleepRmssdValues(fromMs: Long): List<Float>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs DESC LIMIT :limit",
    )
    suspend fun getSleepRmssdValuesSince(
        fromMs: Long,
        limit: Int,
    ): List<Float>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND sessionId = :sessionId " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getSleepRmssdForSession(sessionId: String): List<Float>

    @Query(
        "SELECT sessionId, rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND sessionId IN (:sessionIds) " +
            "ORDER BY sessionId ASC, timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getSleepRmssdForSessionsMap(
        sessionIds: List<String>,
    ): Map<
        @MapColumn(columnName = "sessionId")
        String,
        List<
            @MapColumn(columnName = "rmssdMs")
            Float,
        >,
    >

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND sessionId IN (:sessionIds) " +
            "ORDER BY sessionId ASC, timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getSleepRmssdValuesForSessions(sessionIds: List<String>): List<Float>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE timestampMs >= :fromMs AND timestampMs <= :toMs " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getRmssdInTimeRange(
        fromMs: Long,
        toMs: Long,
    ): List<Float>

    @Query(
        "SELECT * FROM hrv_records WHERE timestampMs >= :fromMs AND timestampMs <= :toMs " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getByTimeRange(
        fromMs: Long,
        toMs: Long,
    ): List<HrvRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HrvRecordEntity>)

    @Query("DELETE FROM hrv_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM hrv_records WHERE sourceRecordId = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM hrv_records WHERE sourceRecordId = :id")
    suspend fun getById(id: String): HrvRecordEntity?

    @Query(
        "SELECT * FROM hrv_records " +
            "WHERE sourceRecordId = :sourceRecordId " +
            "OR (sourceRecordId >= :sourceRecordId || '_' AND sourceRecordId < :sourceRecordId || '`') " +
            "ORDER BY timestampMs ASC, sourceRecordId ASC",
    )
    suspend fun getBySourceRecordId(sourceRecordId: String): List<HrvRecordEntity>

    @Query(
        "DELETE FROM hrv_records " +
            "WHERE sourceRecordId = :sourceRecordId " +
            "OR (sourceRecordId >= :sourceRecordId || '_' AND sourceRecordId < :sourceRecordId || '`')",
    )
    suspend fun deleteBySourceRecordId(sourceRecordId: String): Int

    @Query("SELECT COUNT(*) FROM hrv_records")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM hrv_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs")
    suspend fun countInRange(startMs: Long, endMs: Long): Int

    @Query("DELETE FROM hrv_records")
    suspend fun deleteAll(): Int

    @Query("SELECT DISTINCT deviceName FROM hrv_records WHERE deviceName IS NOT NULL AND deviceName != ''")
    suspend fun getDistinctDeviceNames(): List<String>

    @Query(
        "DELETE FROM hrv_records WHERE timestampMs >= :fromMs AND timestampMs < :toMs AND (deviceName != :deviceName OR deviceName IS NULL)",
    )
    suspend fun deleteRecordsNotMatchingDevice(
        fromMs: Long,
        toMs: Long,
        deviceName: String,
    ): Int

    @Query("SELECT MIN(timestampMs) FROM hrv_records")
    fun observeEarliestHrvTime(): Flow<Long?>
}
