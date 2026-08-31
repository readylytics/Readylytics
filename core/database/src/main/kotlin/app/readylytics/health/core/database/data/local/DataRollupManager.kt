package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hot→warm tier rollup: downsamples raw 1-second heart-rate samples older than the hot/warm
 * boundary into 1-minute `hr_minute_buckets`, then deletes the rolled-up raw rows. The aggregate
 * and the delete run in one transaction so a crash can never drop samples (either the raw rows
 * survive, or they have already been folded into a bucket).
 *
 * R2-DB-004: aggregation runs in Kotlin ([aggregateIntoMinuteBuckets]) rather than a pure-SQL
 * `INSERT...SELECT`, because the warm-tier bucket now also carries a p5/p25/p50/p75/p95
 * percentile sketch and SQLite has no `PERCENTILE_CONT`. `upsertBuckets` (`INSERT OR REPLACE`)
 * keeps the rewrite idempotent -- a re-run over already-rolled minutes overwrites rather than
 * double-counts, matching the previous SQL rollup's contract.
 */
@Singleton
class DataRollupManager
    @Inject
    constructor(
        private val minuteBucketDao: MinuteBucketDao,
        private val heartRateDao: HeartRateDao,
        private val transactionRunner: TransactionRunner,
    ) {
        /** Aggregates and deletes raw heart-rate rows older than [cutoffMs]. Returns rows deleted. */
        suspend fun rollupExpiredHotTier(cutoffMs: Long): Int =
            transactionRunner.runInTransaction {
                val rawSamples = heartRateDao.getPlausibleSamplesBeforeForRollup(cutoffMs)
                if (rawSamples.isNotEmpty()) {
                    minuteBucketDao.upsertBuckets(rawSamples.aggregateIntoMinuteBuckets())
                }
                heartRateDao.deleteBeforeTimestamp(cutoffMs)
            }
    }
