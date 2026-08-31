package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity

@Dao
interface BodyTemperatureRecordDao {
    @Query(
        "SELECT * FROM body_temperature_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getByTimeRange(
        startMs: Long,
        endMs: Long,
    ): List<BodyTemperatureRecordEntity>

    @Upsert
    suspend fun upsertAll(records: List<BodyTemperatureRecordEntity>)


    @Query(
        "SELECT * FROM body_temperature_records " +
            "WHERE timestampMs >= :fromMs AND (" +
            "  timestampMs > :afterTs OR " +
            "  (timestampMs = :afterTs AND id > :afterId)" +
            ") " +
            "ORDER BY timestampMs ASC, id ASC " +
            "LIMIT :limit",
    )
    suspend fun pageAfter(
        fromMs: Long,
        afterTs: Long,
        afterId: String,
        limit: Int,
    ): List<BodyTemperatureRecordEntity>

    @Query("DELETE FROM body_temperature_records WHERE timestampMs < :beforeMs")
    suspend fun deleteBeforeTimestamp(beforeMs: Long): Int

    @Query("DELETE FROM body_temperature_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM body_temperature_records WHERE id = :id")
    suspend fun getById(id: String): BodyTemperatureRecordEntity?

    // PERF-003: sargable range predicate, matches OxygenSaturationRecordDao's rationale.
    @Query(
        "SELECT * FROM body_temperature_records " +
            "WHERE id = :sourceRecordId " +
            "OR (id >= :sourceRecordId || '_' AND id < :sourceRecordId || '`') " +
            "ORDER BY timestampMs ASC",
    )
    suspend fun getBySourceRecordId(sourceRecordId: String): List<BodyTemperatureRecordEntity>

    @Query(
        "DELETE FROM body_temperature_records " +
            "WHERE id = :sourceRecordId " +
            "OR (id >= :sourceRecordId || '_' AND id < :sourceRecordId || '`')",
    )
    suspend fun deleteBySourceRecordId(sourceRecordId: String): Int

    @Query("SELECT COUNT(*) FROM body_temperature_records")
    suspend fun count(): Int

    @Query("DELETE FROM body_temperature_records")
    suspend fun deleteAll(): Int

    @Query(
        "DELETE FROM body_temperature_records " +
            "WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "AND (deviceName != :deviceName OR deviceName IS NULL)",
    )
    suspend fun deleteRecordsNotMatchingDevice(
        fromMs: Long,
        toMs: Long,
        deviceName: String,
    ): Int

    @Query(
        "DELETE FROM body_temperature_records " +
            "WHERE timestampMs >= :startMs AND timestampMs <= :endMs AND id NOT IN (:validIds)",
    )
    suspend fun deleteNotIn(startMs: Long, endMs: Long, validIds: List<String>): Int

    @Query("DELETE FROM body_temperature_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs")
    suspend fun deleteBetween(startMs: Long, endMs: Long): Int
}
