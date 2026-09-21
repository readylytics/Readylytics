package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.ScanTypeStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.StagedDeletionBounds
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.ZoneId

/**
 * WP-18 deletion reconciliation. Every predicate is a set-based anti-join against `scan_seen_ids`,
 * so no statement carries a bind-variable list and nothing proportional to the scanned cardinality
 * is materialized: for each type we read the min/max bounds of the rows about to be deleted (one
 * aggregate), then delete them in one statement — except HR/HRV parents, which are paged by
 * `health_source_records.id` because each one needs `deleteBySourceRecordId`'s
 * contribution-then-row sequence (its `ON DELETE RESTRICT` FK, see that DAO's KDoc).
 *
 * HC-002: returns `null` without touching anything unless the scan of this type is COMPLETE.
 */
internal object StagedDeletionReconciler {
    const val SOURCE_PAGE_SIZE = 500

    suspend fun reconcile(
        daos: HealthRecordDaos,
        vo2MaxRecordDao: Vo2MaxRecordDao,
        scanTypeStateDao: ScanTypeStateDao,
        scan: CompleteTypeScan,
        zoneId: ZoneId,
    ): ScoreInvalidation.AffectedRange? {
        val state = scanTypeStateDao.getState(scan.scan.runId, scan.scan.chunkId, scan.type.name)
        if (state?.state != ScanTypeStateDao.STATE_COMPLETE) return null

        val ctx = StagedScanContext(scan, zoneId)
        return when (scan.type) {
            HealthDataType.SLEEP -> reconcileSleep(daos, ctx)
            HealthDataType.EXERCISE -> reconcileExercise(daos, ctx)
            HealthDataType.HEART_RATE -> reconcileHeartSource(daos, "HEART_RATE", ctx)
            HealthDataType.HRV -> reconcileHeartSource(daos, "HRV", ctx)
            HealthDataType.STEPS -> reconcileSteps(daos, ctx)
            else -> reconcileVitals(daos, vo2MaxRecordDao, scan.type, ctx)
        }
    }

    private suspend fun reconcileSleep(
        daos: HealthRecordDaos,
        ctx: StagedScanContext,
    ): ScoreInvalidation.AffectedRange? {
        val bounds =
            daos.sleepSessionDao.boundsOfUnstagedSessions(
                ctx.startMs,
                ctx.endMs,
                ctx.runId,
                ctx.chunkId,
                ctx.recordType,
            )
        val range = ctx.rangeOf(bounds) ?: return null
        daos.sleepSessionDao.deleteStagesOfUnstagedSessions(
            ctx.startMs,
            ctx.endMs,
            ctx.runId,
            ctx.chunkId,
            ctx.recordType,
        )
        daos.sleepSessionDao.deleteSessionsNotStaged(
            ctx.startMs,
            ctx.endMs,
            ctx.runId,
            ctx.chunkId,
            ctx.recordType,
        )
        return range
    }

    private suspend fun reconcileExercise(
        daos: HealthRecordDaos,
        ctx: StagedScanContext,
    ): ScoreInvalidation.AffectedRange? {
        val bounds =
            daos.workoutDao.boundsOfUnstagedWorkouts(
                ctx.startMs,
                ctx.endMs,
                ctx.runId,
                ctx.chunkId,
                ctx.recordType,
            )
        val range = ctx.rangeOf(bounds) ?: return null
        daos.workoutDao.deleteRoutePointsOfUnstagedWorkouts(
            ctx.startMs,
            ctx.endMs,
            ctx.runId,
            ctx.chunkId,
            ctx.recordType,
        )
        daos.workoutDao.deleteWorkoutsNotStaged(
            ctx.startMs,
            ctx.endMs,
            ctx.runId,
            ctx.chunkId,
            ctx.recordType,
        )
        return range
    }

    private suspend fun reconcileSteps(
        daos: HealthRecordDaos,
        ctx: StagedScanContext,
    ): ScoreInvalidation.AffectedRange? {
        val bounds =
            daos.stepRecordDao.boundsOfUnstagedRecords(
                ctx.startMs,
                ctx.endMs,
                ctx.runId,
                ctx.chunkId,
                ctx.recordType,
            )
        val range = ctx.rangeOf(bounds) ?: return null
        daos.stepRecordDao.deleteRecordsNotStaged(
            ctx.startMs,
            ctx.endMs,
            ctx.runId,
            ctx.chunkId,
            ctx.recordType,
        )
        return range
    }

    private suspend fun reconcileVitals(
        daos: HealthRecordDaos,
        vo2MaxRecordDao: Vo2MaxRecordDao,
        type: HealthDataType,
        ctx: StagedScanContext,
    ): ScoreInvalidation.AffectedRange? {
        val bounds = fetchVitalBounds(daos, vo2MaxRecordDao, type, ctx)
        val range = bounds?.let { ctx.rangeOf(it) } ?: return null
        deleteVitalRows(daos, vo2MaxRecordDao, type, ctx)
        return range
    }

    private suspend fun fetchVitalBounds(
        daos: HealthRecordDaos,
        vo2MaxRecordDao: Vo2MaxRecordDao,
        type: HealthDataType,
        ctx: StagedScanContext,
    ): StagedDeletionBounds? =
        when (type) {
            HealthDataType.WEIGHT ->
                daos.weightRecordDao.boundsOfUnstagedRows(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.BODY_FAT ->
                daos.bodyFatRecordDao.boundsOfUnstagedRows(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.BLOOD_PRESSURE ->
                daos.bloodPressureRecordDao.boundsOfUnstagedRows(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.OXYGEN_SATURATION ->
                daos.oxygenSaturationRecordDao.boundsOfUnstagedRows(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.BODY_TEMPERATURE ->
                daos.bodyTemperatureRecordDao.boundsOfUnstagedRows(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.VO2_MAX ->
                vo2MaxRecordDao.boundsOfUnstagedRows(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            else -> null
        }

    private suspend fun deleteVitalRows(
        daos: HealthRecordDaos,
        vo2MaxRecordDao: Vo2MaxRecordDao,
        type: HealthDataType,
        ctx: StagedScanContext,
    ) {
        when (type) {
            HealthDataType.WEIGHT ->
                daos.weightRecordDao.deleteRowsNotStaged(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.BODY_FAT ->
                daos.bodyFatRecordDao.deleteRowsNotStaged(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.BLOOD_PRESSURE ->
                daos.bloodPressureRecordDao.deleteRowsNotStaged(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.OXYGEN_SATURATION ->
                daos.oxygenSaturationRecordDao.deleteRowsNotStaged(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.BODY_TEMPERATURE ->
                daos.bodyTemperatureRecordDao.deleteRowsNotStaged(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            HealthDataType.VO2_MAX ->
                vo2MaxRecordDao.deleteRowsNotStaged(
                    ctx.startMs,
                    ctx.endMs,
                    ctx.runId,
                    ctx.chunkId,
                    ctx.recordType,
                )
            else -> Unit
        }
    }

    private suspend fun reconcileHeartSource(
        daos: HealthRecordDaos,
        recordType: String,
        ctx: StagedScanContext,
    ): ScoreInvalidation.AffectedRange? {
        var afterRef = Long.MIN_VALUE
        var minMs: Long? = null
        var maxMs: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page =
                daos.sourceRecordDao.pageUnstagedAuthoritativeSources(
                    recordType = recordType,
                    windowStartMs = ctx.startMs,
                    windowEndMs = ctx.endMs,
                    runId = ctx.runId,
                    chunkId = ctx.chunkId,
                    afterRef = afterRef,
                    limit = SOURCE_PAGE_SIZE,
                )
            if (page.isEmpty()) break
            for (source in page) {
                val startMs = source.recordStartMs ?: source.createdAtMs
                val endMs = source.recordEndExclusiveMs?.minus(1L) ?: source.createdAtMs
                minMs = minOf(minMs ?: startMs, startMs)
                maxMs = maxOf(maxMs ?: endMs, endMs)
                // Raw children cascade via the heart_rate_records/hrv_records FK; contributions are
                // removed first inside deleteBySourceRecordId (ON DELETE RESTRICT).
                daos.sourceRecordDao.deleteBySourceRecordId(source.sourceRecordId)
            }
            // Deletion removes the rows this predicate matched, so the keyset advances on the last
            // id seen rather than restarting from MIN_VALUE.
            afterRef = page.last().id
            yield()
        }
        return ctx.rangeOf(StagedDeletionBounds(minMs, maxMs))
    }
}

private class StagedScanContext(
    scan: CompleteTypeScan,
    private val zoneId: ZoneId,
) {
    val startMs = scan.windowStartMs
    val endMs = scan.windowEndExclusiveMs
    val runId = scan.scan.runId
    val chunkId = scan.scan.chunkId
    val recordType = scan.type.name

    fun rangeOf(bounds: StagedDeletionBounds): ScoreInvalidation.AffectedRange? {
        val minMs = bounds.minMs
        val maxMs = bounds.maxMs
        if (minMs == null || maxMs == null) return null
        return ScoreInvalidation.AffectedRange(
            start = Instant.ofEpochMilli(minMs).atZone(zoneId).toLocalDate(),
            endInclusive = Instant.ofEpochMilli(maxMs).atZone(zoneId).toLocalDate(),
        )
    }
}
