package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity

@Dao
interface HeartRateWriteDao {
    @Query(
        "INSERT INTO heart_rate_records " +
            "(sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
            "VALUES (:sourceRecordRef, :timestampMs, :beatsPerMinute, :recordType, :sessionId, :deviceName) " +
            "ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE SET " +
            "beatsPerMinute = excluded.beatsPerMinute, " +
            "recordType = excluded.recordType, " +
            "sessionId = excluded.sessionId, " +
            "deviceName = excluded.deviceName " +
            "WHERE (beatsPerMinute IS NOT excluded.beatsPerMinute OR " +
            "recordType IS NOT excluded.recordType OR " +
            "sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)",
    )
    suspend fun conflictTargetedUpsert(
        sourceRecordRef: Long,
        timestampMs: Long,
        beatsPerMinute: Int,
        recordType: String,
        sessionId: String?,
        deviceName: String?,
    ): Long

    @RawQuery
    suspend fun upsertChunk(query: RoomRawQuery): Int

    @Transaction
    suspend fun upsertAll(records: List<HeartRateRecordEntity>) {
        for (chunk in records.chunked(UPSERT_ROWS_PER_STATEMENT)) {
            upsertChunk(buildUpsertQuery(chunk))
        }
    }

    private fun buildUpsertQuery(records: List<HeartRateRecordEntity>): RoomRawQuery {
        val values = records.joinToString(", ") { "(?, ?, ?, ?, ?, ?)" }
        val sql = "INSERT INTO heart_rate_records " +
            "(sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
            "VALUES $values " +
            "ON CONFLICT(sourceRecordRef, timestampMs) DO UPDATE SET " +
            "beatsPerMinute = excluded.beatsPerMinute, " +
            "recordType = excluded.recordType, " +
            "sessionId = excluded.sessionId, " +
            "deviceName = excluded.deviceName " +
            "WHERE (beatsPerMinute IS NOT excluded.beatsPerMinute OR " +
            "recordType IS NOT excluded.recordType OR " +
            "sessionId IS NOT excluded.sessionId OR deviceName IS NOT excluded.deviceName)"
        return RoomRawQuery(sql) { statement ->
            records.forEachIndexed { index, record ->
                val offset = index * UPSERT_BINDS_PER_ROW
                statement.bindLong(offset + 1, record.sourceRecordRef)
                statement.bindLong(offset + 2, record.timestampMs)
                statement.bindLong(offset + 3, record.beatsPerMinute.toLong())
                statement.bindText(offset + 4, record.recordType)
                record.sessionId?.let { statement.bindText(offset + 5, it) } ?: statement.bindNull(offset + 5)
                record.deviceName?.let { statement.bindText(offset + 6, it) } ?: statement.bindNull(offset + 6)
            }
        }
    }

    @Query("DELETE FROM heart_rate_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM heart_rate_records WHERE timestampMs >= :fromMs AND timestampMs < :toMs")
    suspend fun deleteInRange(
        fromMs: Long,
        toMs: Long,
    ): Int

    @Query(
        "DELETE FROM heart_rate_records WHERE rowId IN (" +
            "SELECT rowId FROM heart_rate_records WHERE timestampMs < :beforeMs " +
            "ORDER BY timestampMs ASC LIMIT :limit" +
            ")",
    )
    suspend fun deleteBeforeTimestampBatch(
        beforeMs: Long,
        limit: Int,
    ): Int

    @Query("DELETE FROM heart_rate_records WHERE sourceRecordRef = :sourceRecordRef")
    suspend fun deleteByRef(sourceRecordRef: Long): Int
}

const val UPSERT_ROWS_PER_STATEMENT = 100
const val UPSERT_BINDS_PER_ROW = 6
