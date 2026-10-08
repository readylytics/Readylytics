package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SelectedSourcePrunerImpl
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val daos: HealthRecordDaos,
        private val vo2MaxRecordDao: Vo2MaxRecordDao? = null,
        // Fix-round-3: durable journal for pruneExcludedWorkouts, so a page's delete and the
        // ticket recording "its dates still need recompute" commit atomically. Nullable default
        // keeps every pre-existing 2/3-arg test construction compiling; Hilt always injects the
        // real bindings in production (both already exist, see RoomDirtyRangeStore/
        // HealthMutationStateDao other callers such as SourcePayloadWriter).
        private val dirtyRangeStore: RoomDirtyRangeStore? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
    ) : SelectedSourcePruner {
        override suspend fun prune(
            start: LocalDate,
            endInclusive: LocalDate,
            selections: Map<HealthDataType, String?>,
            zoneId: ZoneId,
        ) {
            val fromMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val toMs =
                endInclusive
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            transactionRunner.runInTransaction {
                selections.forEach { (type, deviceName) ->
                    if (!deviceName.isNullOrBlank()) {
                        pruneType(type, deviceName, fromMs, toMs)
                    }
                }
            }
        }

        override suspend fun pruneExcludedWorkouts(
            start: LocalDate,
            endInclusive: LocalDate,
            selectedDevice: String,
            zoneId: ZoneId,
        ): ScoreInvalidation.AffectedRange? {
            if (selectedDevice.isBlank()) return null
            val fromMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val toMs = endInclusive.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

            var cursorTs = fromMs - 1
            var cursorId = ""
            var touchedStart: LocalDate? = null
            var touchedEnd: LocalDate? = null

            while (true) {
                currentCoroutineContext().ensureActive()
                val page =
                    daos.workoutDao.pageExcludedByDevice(
                        fromMs,
                        toMs,
                        selectedDevice,
                        cursorTs,
                        cursorId,
                        EXCLUDED_WORKOUT_PAGE_SIZE,
                    )
                if (page.isEmpty()) break

                val ids = page.map { it.id }
                val pageDates = page.map { Instant.ofEpochMilli(it.startTime).atZone(zoneId).toLocalDate() }
                val pageStart = pageDates.min()
                val pageEnd = pageDates.max()
                transactionRunner.runInTransaction {
                    daos.workoutRoutePointDao.deleteForWorkouts(ids)
                    daos.workoutDao.deleteByIds(ids)
                    journalPrunedPage(pageStart, pageEnd, start, endInclusive)
                }

                if (touchedStart == null || pageStart.isBefore(touchedStart)) touchedStart = pageStart
                if (touchedEnd == null || pageEnd.isAfter(touchedEnd)) touchedEnd = pageEnd

                val last = page.last()
                cursorTs = last.startTime
                cursorId = last.id
                yield()
            }

            return touchedStart?.let { ScoreInvalidation.AffectedRange(it, touchedEnd ?: it) }
        }

        /**
         * Fix-round-3 (durability): journals a durable `dirty_ranges` ticket for this page's
         * dependency closure, in the SAME transaction as the page's own delete (the pattern
         * [DeletionJournalContext.deleteRecordsAndJournal]/`SourcePayloadWriter.recordDirtyRange`
         * already use). Without this, the live [ScoreInvalidation.AffectedRange] this function
         * returns was the only record of "these dates need recompute" -- a worker killed after
         * this page's delete committed but before the caller's recompute succeeded would lose that
         * fact forever, since a retry's prune finds nothing left to delete and reports null. With
         * the ticket durable, a retry's recompute drains it regardless of what the retry's own
         * prune finds.
         */
        private suspend fun journalPrunedPage(
            pageStart: LocalDate,
            pageEnd: LocalDate,
            retentionStart: LocalDate,
            today: LocalDate,
        ) {
            val store = dirtyRangeStore ?: return
            val mutationStateDao = healthMutationStateDao ?: return
            ScoreInvalidation
                .dependencyClosure(
                    changed = ScoreInvalidation.AffectedRange(pageStart, pageEnd),
                    reason = ScoreInvalidation.Reason.WORKOUT,
                    retentionStart = retentionStart,
                    today = today,
                )?.let { closure ->
                    mutationStateDao.incrementGeneration()
                    store.append(
                        start = closure.start,
                        endInclusive = closure.endInclusive,
                        reason = "WORKOUT",
                        snapshotId = "ACTIVE",
                    )
                }
        }

        private suspend fun pruneType(
            type: HealthDataType,
            deviceName: String,
            fromMs: Long,
            toMs: Long,
        ) {
            when (type) {
                HealthDataType.SLEEP ->
                    daos.sleepSessionDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                HealthDataType.HEART_RATE -> {
                    daos.heartRateDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                    daos.minuteBucketMaintenanceDao.deleteBucketsNotMatchingDevice(fromMs, toMs, deviceName)
                    daos.minuteBucketMaintenanceDao.deleteContributionsNotMatchingDevice(fromMs, toMs, deviceName)
                }
                HealthDataType.HRV ->
                    daos.hrvDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                HealthDataType.EXERCISE ->
                    daos.workoutDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                HealthDataType.WEIGHT ->
                    daos.weightRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                HealthDataType.BODY_FAT ->
                    daos.bodyFatRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                HealthDataType.BLOOD_PRESSURE ->
                    daos.bloodPressureRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                HealthDataType.OXYGEN_SATURATION ->
                    daos.oxygenSaturationRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                HealthDataType.BODY_TEMPERATURE ->
                    daos.bodyTemperatureRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                HealthDataType.STEPS -> {
                    // Steps are in daily_summaries
                }
                HealthDataType.VO2_MAX ->
                    // No bulk "not matching device" query exists on this DAO (kept small to
                    // stay under detekt's TooManyFunctions threshold) -- reuses the same
                    // getByTimeRange/deleteById pair the deletion reconciler already relies on.
                    vo2MaxRecordDao
                        ?.getByTimeRange(fromMs, toMs)
                        ?.filter { it.deviceName != deviceName }
                        ?.forEach { vo2MaxRecordDao.deleteById(it.id) }
            }
        }

        private companion object {
            const val EXCLUDED_WORKOUT_PAGE_SIZE = 200
        }
    }
