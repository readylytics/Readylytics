package app.readylytics.health.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity

/**
 * Prototype DAO mirroring production `HeartRateDao.upsertAll` / `HrvDao.upsertAll` plus the
 * Room `@Upsert` candidate. Used by [UpsertConflictStrategyInstrumentedTest] to compare
 * row-identity behavior across strategies on the real SQLCipher-backed Room engine.
 */
@Dao
interface UpsertPrototypeDao {
    /** Current production strategy: SQLite REPLACE, which deletes+reinserts on conflict. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAll(records: List<HeartRateRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceAllHrv(records: List<HrvRecordEntity>)

    /** Candidate: Room @Upsert. Generated SQL targets the PRIMARY KEY (rowId), not the secondary unique key. */
    @Upsert
    suspend fun upsertAll(records: List<HeartRateRecordEntity>)

    @Upsert
    suspend fun upsertAllHrv(records: List<HrvRecordEntity>)

    @Query("SELECT * FROM heart_rate_records WHERE sourceRecordRef = :sourceRecordRef AND timestampMs = :timestampMs")
    suspend fun getHeartRate(
        sourceRecordRef: Long,
        timestampMs: Long,
    ): HeartRateRecordEntity?

    @Query("SELECT * FROM hrv_records WHERE sourceRecordRef = :sourceRecordRef AND timestampMs = :timestampMs")
    suspend fun getHrv(
        sourceRecordRef: Long,
        timestampMs: Long,
    ): HrvRecordEntity?

    /** Connection-scoped change count: reads `changes()` on Room's own connection, unlike
     *  `db.openHelper.writableDatabase` which may be a different pooled connection under WAL. */
    @Query("SELECT changes()")
    suspend fun changes(): Long

    @Query("SELECT COUNT(*) FROM heart_rate_records")
    suspend fun countHeartRate(): Int

    @Query("SELECT COUNT(*) FROM hrv_records")
    suspend fun countHrv(): Int

    @Query("DELETE FROM heart_rate_records")
    suspend fun clearHeartRate()

    @Query("DELETE FROM hrv_records")
    suspend fun clearHrv()

    /**
     * Compile-time probe: does Room 2.8's @Query parser accept UPSERT syntax? The test
     * [UpsertConflictStrategyInstrumentedTest] runs the same statement via execSQL; if Room's
     * parser rejects it, this method fails at KSP time and production must use execSQL instead.
     */
    @Query(
        "INSERT INTO heart_rate_records " +
            "(sourceRecordRef, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
            "VALUES (:sourceRecordRef, :timestampMs, :beatsPerMinute, :recordType, :sessionId, :deviceName) " +
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
        beatsPerMinute: Int,
        recordType: String,
        sessionId: String?,
        deviceName: String?,
    ): Long
}
