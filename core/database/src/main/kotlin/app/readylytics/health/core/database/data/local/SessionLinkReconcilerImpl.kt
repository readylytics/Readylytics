package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import java.time.Instant
import app.readylytics.health.core.model.domain.sync.link.SampleLink
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.core.model.domain.sync.link.SessionLinkSweep
import app.readylytics.health.core.model.domain.sync.link.SessionSpan
import app.readylytics.health.core.model.domain.util.logI
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-ingestion session-link reconcile, run **once per sync/resync over the complete range** so
 * the result cannot depend on ingest chunk alignment.
 *
 * WP-17 Step 4 adds a third relink phase between the raw passes and the workout recompute: warm
 * minutes carry their session link *inside* their primary key, so they must be re-keyed rather than
 * re-tagged. [WarmTierRelinker] owns that rewrite. The workout recompute then reads through
 * [AuthoritativeHeartRateReader], so a workout whose HR has already rolled up into the warm tier is
 * recomputed from the relinked warm projection instead of from a raw range that no longer holds it.
 */
@Singleton
class SessionLinkReconcilerImpl
    @Inject
    constructor(
        private val sleepSessionDao: SleepSessionDao,
        private val workoutDao: WorkoutDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val transactionRunner: TransactionRunner,
        private val authoritativeReader: AuthoritativeHeartRateReader,
        // Absent only in fixtures that have no warm tier at all; production DI always binds it
        // (same optional-collaborator shape DataRollupManager/MinuteCoveragePublisher already use).
        private val warmTierRelinker: WarmTierRelinker? = null,
    ) : SessionLinkReconciler {
        override suspend fun reconcile(
            startMs: Long,
            endMs: Long,
            zoneThresholds: IntArray,
        ) {
            val sleepSpans =
                sleepSessionDao
                    .getOverlapping(startMs, endMs)
                    .map { SessionSpan(it.id, it.startTime, it.endTime) }
            val workoutSpans =
                workoutDao
                    .getOverlapping(startMs, endMs)
                    .map { SessionSpan(it.id, it.startTime, it.endTime) }

            relinkHeartRate(startMs, endMs, sleepSpans, workoutSpans)
            relinkHrv(startMs, endMs, sleepSpans)
            // Must run before the workout recompute: recomputeWorkouts reads the warm projection
            // through the authoritative reader, so it has to see the re-keyed buckets, not the
            // previous pass's. Runs once over the same complete range, from stable evidence.
            warmTierRelinker?.relink(startMs, endMs, sleepSpans, workoutSpans)?.let { outcome ->
                // WP-17 Steps 4/5 require the legacy/unresolved minutes to be measurably flagged on
                // a real device, not only in tests. This is the only production observation point
                // for the relink's counts, so it logs unconditionally (once per reconcile, not per
                // minute): `unresolvedLegacy` is how much history is still the pre-v22 approximation
                // that no in-app action can repair, and `unresolvedEvidence` is a corrupt-histogram
                // signal that would otherwise be invisible outside WarmTierRelinkTest.
                logI(RECONCILE_TAG) {
                    "Warm relink over [$startMs, $endMs]: inspected=${outcome.inspected} " +
                        "republished=${outcome.republished} retired=${outcome.retired} " +
                        "unresolvedLegacy=${outcome.unresolvedLegacy} " +
                        "unresolvedEvidence=${outcome.unresolvedEvidence}"
                }
            }
            recomputeWorkouts(workoutSpans, zoneThresholds)
        }

        private suspend fun relinkHeartRate(
            startMs: Long,
            endMs: Long,
            sleepSpans: List<SessionSpan>,
            workoutSpans: List<SessionSpan>,
        ) {
            var lastTimestampMs = 0L
            var lastSourceRecordRef = 0L
            val limit = 5000
            val sweep = SessionLinkSweep(sleepSpans, workoutSpans)
            while (true) {
                currentCoroutineContext().ensureActive()
                val records = heartRateDao.getKeysetPage(
                    startMs = startMs,
                    endMs = endMs,
                    lastTimestampMs = lastTimestampMs,
                    lastSourceRecordRef = lastSourceRecordRef,
                    limit = limit
                )
                if (records.isEmpty()) break

                val updated = records.mapNotNull { record ->
                    val link = sweep.resolve(record.timestampMs)
                    record.relinkedOrNull(link)
                }

                if (updated.isNotEmpty()) {
                    transactionRunner.runInTransaction {
                        heartRateDao.upsertAll(updated)
                    }
                }

                val lastRecord = records.last()
                lastTimestampMs = lastRecord.timestampMs
                lastSourceRecordRef = lastRecord.sourceRecordRef

                yield()
            }
        }

        private suspend fun relinkHrv(
            startMs: Long,
            endMs: Long,
            sleepSpans: List<SessionSpan>,
        ) {
            var lastTimestampMs = 0L
            var lastSourceRecordRef = 0L
            val limit = 5000
            val sweep = SessionLinkSweep(sleepSpans, emptyList())
            while (true) {
                currentCoroutineContext().ensureActive()
                val records = hrvDao.getKeysetPage(
                    startMs = startMs,
                    endMs = endMs,
                    lastTimestampMs = lastTimestampMs,
                    lastSourceRecordRef = lastSourceRecordRef,
                    limit = limit
                )
                if (records.isEmpty()) break

                val updated = records.mapNotNull { record ->
                    val link = sweep.resolve(record.timestampMs)
                    record.relinkedOrNull(link)
                }

                if (updated.isNotEmpty()) {
                    transactionRunner.runInTransaction {
                        hrvDao.upsertAll(updated)
                    }
                }

                val lastRecord = records.last()
                lastTimestampMs = lastRecord.timestampMs
                lastSourceRecordRef = lastRecord.sourceRecordRef

                yield()
            }
        }

        private suspend fun recomputeWorkouts(
            workoutSpans: List<SessionSpan>,
            zoneThresholds: IntArray,
        ) {
            // HC-002: batched in groups of WORKOUT_BATCH_SIZE instead of one DB read + one
            // transaction per workout. workoutSpans is ordered by startTime ASC (WorkoutDao.getOverlapping),
            // so a chunk's own time span stays local to ~WORKOUT_BATCH_SIZE chronologically-adjacent
            // workouts rather than spanning the whole reconcile range.
            for (batch in workoutSpans.chunked(WORKOUT_BATCH_SIZE)) {
                currentCoroutineContext().ensureActive()
                val existingById = workoutDao.getByIds(batch.map { it.id }).associateBy { it.id }
                if (existingById.isEmpty()) {
                    yield()
                    continue
                }

                val batchStartMs = batch.minOf { it.startTime }
                val batchEndMs = batch.maxOf { it.endTime }
                // WP-17 Step 3: raw rows of minutes already served by the warm tier are excluded
                // here, and each workout's own warm slices are added below -- so a workout
                // straddling the hot/warm cutoff contributes each of its minutes exactly once.
                val rawInBatch =
                    authoritativeReader
                        .rawByTypeInRange(RecordType.EXERCISE.name, batchStartMs, batchEndMs)
                        .map { it.toDomainSample() }

                val updated =
                    batch.mapNotNull { span ->
                        val existing = existingById[span.id] ?: return@mapNotNull null
                        val warm =
                            authoritativeReader
                                .warmSessionSamples(RecordType.EXERCISE.name, span.id)
                                .map { it.toDomainSample() }
                        val hrSamplesMapped = if (warm.isEmpty()) rawInBatch else rawInBatch + warm
                        val metrics =
                            ZoneThresholds.computeMetrics(
                                existing.startTime,
                                existing.endTime,
                                hrSamplesMapped,
                                zoneThresholds,
                            )
                        existing.copy(
                            durationMinutes = metrics.durationMinutes,
                            zone1Minutes = metrics.zoneMinutes[0],
                            zone2Minutes = metrics.zoneMinutes[1],
                            zone3Minutes = metrics.zoneMinutes[2],
                            zone4Minutes = metrics.zoneMinutes[3],
                            zone5Minutes = metrics.zoneMinutes[4],
                            trimp = metrics.trimp,
                            avgHr = metrics.avgHr,
                        )
                    }

                if (updated.isNotEmpty()) {
                    transactionRunner.runInTransaction {
                        workoutDao.upsertAll(updated)
                    }
                }
                yield()
            }
        }

        private companion object {
            private const val WORKOUT_BATCH_SIZE = 20
            private const val RECONCILE_TAG = "SessionLinkReconciler"
        }
    }

private fun HeartRateRecordEntity.toDomainSample(): DomainHeartRateSample =
    DomainHeartRateSample(
        time = Instant.ofEpochMilli(timestampMs),
        beatsPerMinute = beatsPerMinute,
    )

private fun HeartRateRecordEntity.relinkedOrNull(link: SampleLink): HeartRateRecordEntity? =
    if (recordType != link.recordType || sessionId != link.sessionId) {
        copy(recordType = link.recordType, sessionId = link.sessionId)
    } else {
        null
    }

private fun HrvRecordEntity.relinkedOrNull(link: SampleLink): HrvRecordEntity? =
    if (recordType != link.recordType || sessionId != link.sessionId) {
        copy(recordType = link.recordType, sessionId = link.sessionId)
    } else {
        null
    }
