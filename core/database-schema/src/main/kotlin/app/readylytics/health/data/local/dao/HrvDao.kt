package app.readylytics.health.data.local.dao

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import app.readylytics.health.data.local.entity.HrvRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface HrvDao {
    @Query("SELECT * FROM hrv_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordRef ASC")
    fun _observeSince(fromMs: Long): Flow<List<HrvRecordEntity>>

    fun observeSince(fromMs: Long): Flow<List<HrvRecordEntity>> = _observeSince(fromMs).distinctUntilChanged()

    @Query(
        "SELECT * FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    fun _observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordEntity>>

    fun observeSleepHrvSince(fromMs: Long): Flow<List<HrvRecordEntity>> =
        _observeSleepHrvSince(fromMs).distinctUntilChanged()

    @Query("SELECT * FROM hrv_records WHERE timestampMs >= :fromMs ORDER BY timestampMs ASC, sourceRecordRef ASC")
    suspend fun getSince(fromMs: Long): List<HrvRecordEntity>


    @Query(
        "SELECT * FROM hrv_records " +
            "WHERE timestampMs >= :fromMs AND (" +
            "  timestampMs > :afterTs OR " +
            "  (timestampMs = :afterTs AND sourceRecordRef > :afterRef)" +
            ") " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterRef: Long,
        limit: Int,
    ): List<HrvRecordEntity>

    @Query(
        "SELECT * FROM hrv_records " +
            "WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "AND (timestampMs > :lastTimestampMs OR (timestampMs = :lastTimestampMs AND sourceRecordRef > :lastSourceRecordRef)) " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC LIMIT :limit",
    )
    suspend fun getKeysetPage(
        startMs: Long,
        endMs: Long,
        lastTimestampMs: Long,
        lastSourceRecordRef: Long,
        limit: Int,
    ): List<HrvRecordEntity>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
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
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSleepRmssdForSession(sessionId: String): List<Float>

    @Query(
        "SELECT sessionId, rmssdMs FROM hrv_records WHERE recordType = 'SLEEP' AND sessionId IN (:sessionIds) " +
            "ORDER BY sessionId ASC, timestampMs ASC, sourceRecordRef ASC",
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
            "ORDER BY sessionId ASC, timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSleepRmssdValuesForSessions(sessionIds: List<String>): List<Float>

    @Query(
        "SELECT rmssdMs FROM hrv_records WHERE timestampMs >= :fromMs AND timestampMs <= :toMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getRmssdInTimeRange(
        fromMs: Long,
        toMs: Long,
    ): List<Float>

    @Query(
        "SELECT * FROM hrv_records WHERE timestampMs >= :fromMs AND timestampMs <= :toMs " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getByTimeRange(
        fromMs: Long,
        toMs: Long,
    ): List<HrvRecordEntity>

    // Conflict-targeted UPSERT on the natural unique key (sourceRecordRef, timestampMs): updates
    // mutable columns (recordType/sessionId/deviceName) in place and preserves rowId — unlike
    // SQLite REPLACE, which deletes+reinserts and rotates rowId on every re-upsert.
    @Query(
        "INSERT INTO hrv_records " +
            "(sourceRecordRef, timestampMs, rmssdMs, recordType, sessionId, deviceName) " +
            "VALUES (:sourceRecordRef, :timestampMs, :rmssdMs, :recordType, :sessionId, :deviceName) " +
            "ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE SET " +
            "recordType = excluded.recordType, " +
            "sessionId = excluded.sessionId, " +
            "deviceName = excluded.deviceName " +
            "WHERE (recordType IS NOT excluded.recordType OR " +
            "sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)",
    )
    suspend fun conflictTargetedUpsert(
        sourceRecordRef: Long,
        timestampMs: Long,
        rmssdMs: Float,
        recordType: String,
        sessionId: String?,
        deviceName: String?,
    ): Long

    suspend fun upsertAll(records: List<HrvRecordEntity>) {
        for (record in records) {
            conflictTargetedUpsert(
                sourceRecordRef = record.sourceRecordRef,
                timestampMs = record.timestampMs,
                rmssdMs = record.rmssdMs,
                recordType = record.recordType,
                sessionId = record.sessionId,
                deviceName = record.deviceName,
            )
        }
    }

    @Query("DELETE FROM hrv_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM hrv_records WHERE sourceRecordRef = :sourceRecordRef")
    suspend fun deleteByRef(sourceRecordRef: Long): Int

    @Query("SELECT * FROM hrv_records WHERE sourceRecordRef = :sourceRecordRef")
    suspend fun getByRef(sourceRecordRef: Long): HrvRecordEntity?

    @Query(
        "SELECT * FROM hrv_records " +
            "WHERE sourceRecordRef = :sourceRecordRef " +
            "ORDER BY timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getBySourceRecordRef(sourceRecordRef: Long): List<HrvRecordEntity>

    @Query(
        "DELETE FROM hrv_records WHERE sourceRecordRef = :sourceRecordRef",
    )
    suspend fun deleteBySourceRecordRef(sourceRecordRef: Long): Int

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
