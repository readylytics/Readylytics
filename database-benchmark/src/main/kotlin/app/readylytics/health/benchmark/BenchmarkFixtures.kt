package app.readylytics.health.benchmark

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import kotlinx.coroutines.runBlocking

/** Phase-0 (R2) raw seeding helpers for the :database-benchmark module. */
internal object BenchmarkFixtures {
    private const val INSERT_TRANSACTION_ROWS = 5_000

    /**
     * Inserts [days] days of 1 Hz heart-rate rows starting at [startTimeMs] via one prepared
     * statement in 5,000-row transactions. Rows cycle [recordType] between the given types and
     * [sessionId] between the given ids so rollup produces both session-bound and unbound buckets.
     * Returns the row count.
     */
    fun seedHeartRateRows(
        database: HealthDatabase,
        startTimeMs: Long,
        days: Int,
        sessionIds: List<String>,
        recordTypes: List<String>,
    ): Long {
        val rows = days * 86_400L
        val sql =
            "INSERT INTO heart_rate_records " +
                "(sourceRecordId, timestampMs, beatsPerMinute, recordType, sessionId, deviceName) " +
                "VALUES (?, ?, ?, ?, ?, ?)"
        val db = database.openHelper.writableDatabase
        db.compileStatement(sql).use { statement ->
            var batchStart = 0L
            while (batchStart < rows) {
                val batchEnd = minOf(batchStart + INSERT_TRANSACTION_ROWS, rows)
                db.beginTransaction()
                try {
                    for (i in batchStart until batchEnd) {
                        val timestampMs = startTimeMs + i * 1_000L
                        statement.clearBindings()
                        statement.bindString(1, "bench-src-${i / 60}")
                        statement.bindLong(2, timestampMs)
                        statement.bindLong(3, (55 + (i % 121)).toLong())
                        statement.bindString(4, recordTypes[(i % recordTypes.size).toInt()])
                        statement.bindString(5, sessionIds[(i % sessionIds.size).toInt()])
                        statement.bindString(6, "bench-device-${(i % 2).toInt()}")
                        statement.executeInsert()
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                batchStart = batchEnd
            }
        }
        return rows
    }

    /** Inserts [bucketCount] warm buckets at 1-minute spacing starting at [startTimeMs]. */
    fun seedWarmBuckets(
        database: HealthDatabase,
        startTimeMs: Long,
        bucketCount: Int,
        recordType: String,
        sessionId: String,
        sampleCount: Int,
    ) {
        val entities =
            (0 until bucketCount).map { i ->
                val start = startTimeMs + i * 60_000L
                HrMinuteBucketEntity(
                    bucketStartMs = start,
                    bucketEndMs = start + 60_000L,
                    minBpm = 55,
                    maxBpm = 67,
                    avgBpm = 61.0,
                    sampleCount = sampleCount,
                    recordType = recordType,
                    sessionId = sessionId,
                )
            }
        runBlocking { database.minuteBucketDao().upsertBuckets(entities) }
    }

    /**
     * Records the allocation delta (bytes) of a single [block] pass via android.os.Debug. The
     * androidx.benchmark 1.5.0-rc02 artifacts on this classpath no longer ship AllocationMetric,
     * so these deltas are the Phase-0 allocation baseline for R2-PERF-001/003/004. Printed to
     * stdout so the instrumentation output captures the number.
     */
    fun recordAllocationDelta(
        label: String,
        block: () -> Unit,
    ) {
        val before = android.os.Debug.getGlobalAllocSize()
        block()
        val delta = android.os.Debug.getGlobalAllocSize() - before
        println("R2BENCH $label allocations=$delta bytes")
    }
}
