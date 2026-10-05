package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.model.domain.model.SleepHrSample

@Dao
interface HeartRateSleepDao {
    @Query(
        "SELECT CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId " +
            "AND beatsPerMinute BETWEEN 30 AND 230",
    )
    suspend fun getAvgSleepHr(sessionId: String): Int?

    @Query(
        "SELECT sessionId, CAST(ROUND(AVG(beatsPerMinute)) AS INTEGER) AS avgHr FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId IN (:sessionIds) " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
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
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "GROUP BY sessionId",
    )
    suspend fun getAvgSleepHrPerSession(fromMs: Long): List<Int>

    @Query(
        "SELECT beatsPerMinute FROM heart_rate_records " +
            "WHERE sessionId = :sessionId AND recordType = 'SLEEP' " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSleepHrSamplesForSession(sessionId: String): List<Int>

    @Query(
        "SELECT rowId, sourceRecordRef, sessionId, recordType, beatsPerMinute, timestampMs, deviceName " +
            "FROM heart_rate_records " +
            "WHERE sessionId IN (:sessionIds) AND recordType = 'SLEEP' " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSleepHrSamplesForSessions(sessionIds: List<String>): List<HeartRateRecordEntity>

    @Query(
        "SELECT sessionId, beatsPerMinute " +
            "FROM heart_rate_records " +
            "WHERE sessionId IN (:sessionIds) AND recordType = 'SLEEP' " +
            "AND beatsPerMinute BETWEEN 30 AND 230 " +
            "ORDER BY sessionId ASC, beatsPerMinute ASC, timestampMs ASC, sourceRecordRef ASC",
    )
    suspend fun getSleepHrProjectionForSessions(sessionIds: List<String>): List<SleepHrSample>
}
