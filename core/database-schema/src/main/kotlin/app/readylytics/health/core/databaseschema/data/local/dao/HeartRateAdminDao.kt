package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateAdminDao {
    @Query("SELECT COUNT(*) FROM heart_rate_records")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM heart_rate_records WHERE timestampMs >= :startMs AND timestampMs <= :endMs")
    suspend fun countInRange(startMs: Long, endMs: Long): Int

    @Query("DELETE FROM heart_rate_records")
    suspend fun deleteAll(): Int

    @Query("SELECT DISTINCT deviceName FROM heart_rate_records WHERE deviceName IS NOT NULL AND deviceName != ''")
    suspend fun getDistinctDeviceNames(): List<String>

    @Query(
        "DELETE FROM heart_rate_records " +
            "WHERE timestampMs >= :fromMs AND timestampMs < :toMs " +
            "AND (deviceName != :deviceName OR deviceName IS NULL)",
    )
    suspend fun deleteRecordsNotMatchingDevice(
        fromMs: Long,
        toMs: Long,
        deviceName: String,
    ): Int

    @Query("SELECT MIN(timestampMs) FROM heart_rate_records")
    fun observeEarliestHrTime(): Flow<Long?>
}
