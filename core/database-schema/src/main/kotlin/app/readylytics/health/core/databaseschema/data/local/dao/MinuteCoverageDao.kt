package app.readylytics.health.core.databaseschema.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity

@Dao
abstract class MinuteCoverageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertCoverage(coverage: MinuteCoverageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertContributions(contributions: List<HrSourceMinuteContributionEntity>)

    @Query("SELECT * FROM minute_coverage WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs")
    abstract fun getCoverageInRange(startMs: Long, endMs: Long): List<MinuteCoverageEntity>

    @Query("DELETE FROM minute_coverage WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs")
    abstract fun deleteCoverageInRange(startMs: Long, endMs: Long)

    @Query("DELETE FROM hr_source_minute_contributions WHERE bucketStartMs >= :startMs AND bucketStartMs < :endMs")
    abstract fun deleteContributionsInRange(startMs: Long, endMs: Long)

    @Query("SELECT * FROM minute_coverage WHERE bucketStartMs > :afterMs ORDER BY bucketStartMs ASC LIMIT :limit")
    abstract fun pageCoverageAfter(afterMs: Long, limit: Int): List<MinuteCoverageEntity>

    @Query(
        "SELECT * FROM hr_source_minute_contributions " +
        "WHERE bucketStartMs > :afterMs ORDER BY bucketStartMs ASC LIMIT :limit"
    )
    abstract fun pageContributionsAfter(afterMs: Long, limit: Int): List<HrSourceMinuteContributionEntity>

    @Query("SELECT COUNT(*) FROM minute_coverage")
    abstract fun countCoverage(): Long

    @Query("SELECT COUNT(*) FROM hr_source_minute_contributions")
    abstract fun countContributions(): Long
}
