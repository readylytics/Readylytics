package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.databaseschema.data.local.dao.upsertIntervalSourceRecord
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.IntervalKind
import app.readylytics.health.core.model.domain.sync.IntervalSourceRecord
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SessionSpans
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.sync.overlaps
import app.readylytics.health.core.model.domain.util.RetentionBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHealthChangeIngestionStore
    @Inject
    constructor(
        private val daos: HealthRecordDaos,
        private val dirtyRangeStore: RoomDirtyRangeStore? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
        private val settingsRepo: SettingsRepository? = null,
        private val clock: Clock = Clock.systemDefaultZone(),
        private val transactionRunner: TransactionRunner? = null,
        private val vo2MaxRecordDao: Vo2MaxRecordDao? = null,
    ) : HealthChangeIngestionStore {
        private val deletionJournalContext =
            DeletionJournalContext(
                daos, vo2MaxRecordDao, dirtyRangeStore, healthMutationStateDao, settingsRepo, transactionRunner,
            )

        override suspend fun affectedDatesForRecords(
            type: HealthDataType,
            ids: List<String>,
            zoneId: ZoneId,
        ): Set<LocalDate> {
            if (ids.isEmpty()) return emptySet()
            val dates = mutableSetOf<LocalDate>()
            ids.chunked(500).forEach { chunk ->
                dates.addAll(datesForChunk(daos, vo2MaxRecordDao, type, chunk, zoneId))
            }
            return dates
        }

        override suspend fun deleteRecords(type: HealthDataType, ids: List<String>) {
            if (ids.isEmpty()) return
            val zoneId =
                try {
                    settingsRepo?.userPreferences?.first()?.scoringZone() ?: clock.zone
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    clock.zone
                }
            deleteRecordsAndJournal(deletionJournalContext, type, ids, zoneId, LocalDate.now(clock.withZone(zoneId)))
        }

        override suspend fun sessionSpansOverlapping(startMs: Long, endMs: Long): SessionSpans =
            SessionSpans(
                sleepSessions = daos.sleepSessionDao.getOverlapping(startMs, endMs).map { it.toInput() },
                workouts = daos.workoutDao.getOverlapping(startMs, endMs).map { it.toInput() },
            )

        override suspend fun heartRateSamplesForMetrics(
            recordType: String,
            startMs: Long,
            endMs: Long,
        ): List<DomainHeartRateSample> =
            daos.heartRateDao.getByTypeAndTimeRange(recordType, startMs, endMs).map {
                DomainHeartRateSample(time = Instant.ofEpochMilli(it.timestampMs), beatsPerMinute = it.beatsPerMinute)
            }

        override suspend fun persistPreparedWorkouts(prepared: List<PreparedWorkout>) {
            if (prepared.isEmpty()) return
            transactionRunner.runOrDirect {
                val entities = prepared.map { it.toMergedEntity(daos.workoutDao.getById(it.workout.id)) }
                daos.workoutDao.upsertAll(entities)
                prepared.forEach { applyRoutePoints(daos, it) }
            }
        }

        override suspend fun workoutsOverlapping(startMs: Long, endMs: Long): List<WorkoutInput> =
            daos.workoutDao.getOverlapping(startMs, endMs)
                .filter { overlaps(it.startTime, it.endTime, startMs, endMs) }
                .map { it.toInput() }

        override suspend fun getIntervalSource(sourceId: String): IntervalSourceRecord? {
            val entity = daos.sourceRecordDao.getBySourceRecordId(sourceId)
            val kind = when (entity?.recordType) {
                "DISTANCE" -> IntervalKind.DISTANCE
                "ELEVATION_GAINED" -> IntervalKind.ELEVATION_GAINED
                else -> null
            }
            return if (entity != null && kind != null) {
                IntervalSourceRecord(
                    sourceId = entity.sourceRecordId,
                    kind = kind,
                    startMs = entity.recordStartMs ?: 0L,
                    endExclusiveMs = entity.recordEndExclusiveMs ?: 0L,
                    originPackage = entity.originPackage,
                    lastModifiedMs = entity.lastModifiedMs,
                )
            } else {
                null
            }
        }

        override suspend fun persistIntervalEnrichment(
            preparedWorkouts: List<PreparedWorkout>,
            sourceUpserts: List<IntervalSourceRecord>,
            sourceDeletes: List<String>,
            dirtyDates: Set<LocalDate>,
        ) {
            transactionRunner.runOrDirect {
                if (preparedWorkouts.isNotEmpty()) {
                    val entities = preparedWorkouts.map { it.toMergedEntity(daos.workoutDao.getById(it.workout.id)) }
                    daos.workoutDao.upsertAll(entities)
                    preparedWorkouts.forEach { applyRoutePoints(daos, it) }
                }
                sourceUpserts.forEach { record ->
                    daos.sourceRecordDao.upsertIntervalSourceRecord(
                        sourceRecordId = record.sourceId,
                        recordType = record.kind.name,
                        startMs = record.startMs,
                        endExclusiveMs = record.endExclusiveMs,
                        originPackage = record.originPackage,
                        lastModifiedMs = record.lastModifiedMs,
                    )
                }
                sourceDeletes.forEach { sourceId ->
                    daos.sourceRecordDao.deleteBySourceRecordId(sourceId)
                }
                if (dirtyDates.isNotEmpty() && dirtyRangeStore != null && healthMutationStateDao != null) {
                    val today = LocalDate.now(clock)
                    val earliest = dirtyDates.minOrNull()!!
                    val latest = dirtyDates.maxOrNull()!!
                    
                    val prefs = settingsRepo?.userPreferences?.first()
                    val retentionStart = RetentionBounds.resolveResyncStartDate(prefs ?: UserPreferences(), today)
                    val closure =
                        ScoreInvalidation.dependencyClosure(
                            changed = ScoreInvalidation.AffectedRange(earliest, latest),
                            reason = ScoreInvalidation.reasonFromStored("INTERVAL_CORRECTION"),
                            retentionStart = retentionStart,
                            today = today,
                        )
                    if (closure != null) {
                        healthMutationStateDao.incrementGeneration()
                        dirtyRangeStore.append(
                            start = closure.start,
                            endInclusive = closure.endInclusive,
                            reason = "INTERVAL_CORRECTION",
                            snapshotId = "ACTIVE",
                        )
                    }
                }
            }
        }
    }


