package com.gregor.lauritz.healthdashboard.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateDao {
    @Query(
        "SELECT * FROM heart_rate_records WHERE recordType = 'SLEEP' AND timestampMs >= :fromMs " +
            "ORDER BY timestampMs ASC",
    )
    fun observeSleepHrSince(fromMs: Long): Flow<List<HeartRateRecordEntity>>

    @Query(
        "SELECT MIN(beatsPerMinute) FROM heart_rate_records " +
            "WHERE recordType = 'SLEEP' AND sessionId = :sessionId",
    )
    suspend fun getMinSleepHr(sessionId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<HeartRateRecordEntity>)
}
