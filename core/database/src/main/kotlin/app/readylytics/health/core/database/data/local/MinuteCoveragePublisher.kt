package app.readylytics.health.core.database.data.local

import androidx.room.withTransaction
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity

class MinuteCoveragePublisher(
    private val database: HealthDatabase
) {
    suspend fun publish(
        startMs: Long,
        endMs: Long,
        coverage: List<MinuteCoverageEntity>,
        contributions: List<HrSourceMinuteContributionEntity>,
        buckets: List<HrMinuteBucketEntity>,
        dirtyRange: DirtyRangeEntity?
    ) {
        database.withTransaction {
            // Remove old coverage and contributions
            database.minuteCoverageDao().deleteCoverageInRange(startMs, endMs)
            database.minuteCoverageDao().deleteContributionsInRange(startMs, endMs)
            // Remove old buckets
            database.minuteBucketDao().deleteInRange(startMs, endMs)

            // Insert new coverage
            coverage.forEach { database.minuteCoverageDao().insertCoverage(it) }
            database.minuteCoverageDao().insertContributions(contributions)

            // Insert new buckets
            database.minuteBucketDao().upsertBuckets(buckets)

            // Add dirty range
            dirtyRange?.let { database.dirtyRangeDao().insert(it) }
        }
    }
}
