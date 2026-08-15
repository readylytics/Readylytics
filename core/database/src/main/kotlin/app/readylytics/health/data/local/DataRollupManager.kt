package app.readylytics.health.data.local

import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.MinuteBucketDao
import app.readylytics.health.domain.repository.TransactionRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hot→warm tier rollup: downsamples raw 1-second heart-rate samples older than the hot/warm
 * boundary into 1-minute `hr_minute_buckets`, then deletes the rolled-up raw rows. The aggregate
 * and the delete run in one transaction so a crash can never drop samples (either the raw rows
 * survive, or they have already been folded into a bucket).
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
                minuteBucketDao.rollupIntoBucketsBefore(cutoffMs)
                heartRateDao.deleteBeforeTimestamp(cutoffMs)
            }
    }
