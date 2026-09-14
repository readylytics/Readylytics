package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.readylytics.health.core.model.domain.model.DomainIntervalTotal
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.IntervalChange
import app.readylytics.health.core.model.domain.sync.IntervalSourceRecord
import app.readylytics.health.core.model.domain.sync.PreparedWorkout
import app.readylytics.health.core.model.domain.sync.WorkoutInput
import app.readylytics.health.core.model.domain.sync.overlaps
import app.readylytics.health.core.model.domain.util.SessionTotalsResolver
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logW
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Refreshes workout distance and elevation enrichment independently of workout parent records
 * when interval changes or deletions occur (H4/WP-08, OD-4 gate).
 *
 * Remote Health Connect reads execute strictly outside Room writer transactions. Refreshed
 * enrichments, source metadata mutations, and dirty range journal entries commit atomically.
 */
@Singleton
class WorkoutEnrichmentRefresher
    @Inject
    constructor(
        private val client: HealthConnectClient,
        private val changeIngestionStore: HealthChangeIngestionStore,
    ) {
        suspend fun refreshForIntervalChanges(
            changes: List<IntervalChange>,
            zoneId: ZoneId,
        ): Set<LocalDate> {
            val (extents, sourceUpserts, sourceDeletes) = partitionChanges(changes)
            if (extents.isEmpty() && sourceUpserts.isEmpty() && sourceDeletes.isEmpty()) {
                return emptySet()
            }

            val affectedDates = mutableSetOf<LocalDate>()
            for (extent in extents) {
                affectedDates.addAll(
                    getDatesBetween(Instant.ofEpochMilli(extent.first), Instant.ofEpochMilli(extent.second), zoneId),
                )
            }

            val preparedWorkouts = prepareOverlappingWorkouts(extents, zoneId, affectedDates)

            changeIngestionStore.persistIntervalEnrichment(
                preparedWorkouts = preparedWorkouts,
                sourceUpserts = sourceUpserts,
                sourceDeletes = sourceDeletes,
                dirtyDates = affectedDates,
            )

            return affectedDates
        }

        private suspend fun partitionChanges(changes: List<IntervalChange>): PartitionedIntervalChanges {
            val extents = mutableListOf<Pair<Long, Long>>()
            val sourceUpserts = mutableListOf<IntervalSourceRecord>()
            val sourceDeletes = mutableListOf<String>()

            for (change in changes) {
                processSingleChange(change, extents, sourceUpserts, sourceDeletes)
            }
            return PartitionedIntervalChanges(extents, sourceUpserts, sourceDeletes)
        }

        private suspend fun processSingleChange(
            change: IntervalChange,
            extents: MutableList<Pair<Long, Long>>,
            sourceUpserts: MutableList<IntervalSourceRecord>,
            sourceDeletes: MutableList<String>,
        ) {
            val oldStart = change.oldStartMs
            val oldEnd = change.oldEndExclusiveMs
            val newStart = change.newStartMs
            val newEnd = change.newEndExclusiveMs

            if (oldStart != null && oldEnd != null) {
                extents.add(oldStart to oldEnd)
            }
            if (newStart != null && newEnd != null) {
                extents.add(newStart to newEnd)
                sourceUpserts.add(
                    IntervalSourceRecord(
                        sourceId = change.sourceId,
                        kind = change.kind,
                        startMs = newStart,
                        endExclusiveMs = newEnd,
                        originPackage = change.originPackage,
                    ),
                )
            } else if (newStart == null && oldStart == null) {
                val existing = changeIngestionStore.getIntervalSource(change.sourceId)
                if (existing != null) {
                    extents.add(existing.startMs to existing.endExclusiveMs)
                    sourceDeletes.add(change.sourceId)
                } else {
                    logW("WorkoutEnrichmentRefresher") {
                        "Unknown legacy interval identity for deletion: ${change.sourceId}; " +
                            "skipping unmapped total clearance"
                    }
                }
            } else if (newStart == null && oldStart != null) {
                sourceDeletes.add(change.sourceId)
            }
        }

        private suspend fun prepareOverlappingWorkouts(
            extents: List<Pair<Long, Long>>,
            zoneId: ZoneId,
            affectedDates: MutableSet<LocalDate>,
        ): List<PreparedWorkout> {
            if (extents.isEmpty()) return emptyList()
            val minStart = extents.minOf { it.first }
            val maxEnd = extents.maxOf { it.second }
            val candidates = changeIngestionStore.workoutsOverlapping(minStart, maxEnd)
            val matchingWorkouts =
                candidates.filter { workout ->
                    extents.any { (extStart, extEnd) ->
                        overlaps(workout.startTime, workout.endTime, extStart, extEnd)
                    }
                }

            val preparedWorkouts = mutableListOf<PreparedWorkout>()
            matchingWorkouts.chunked(BATCH_SIZE).forEach { batch ->
                for (workout in batch) {
                    affectedDates.addAll(
                        getDatesBetween(
                            Instant.ofEpochMilli(workout.startTime),
                            Instant.ofEpochMilli(workout.endTime),
                            zoneId,
                        ),
                    )
                    preparedWorkouts.add(prepareEnrichment(workout))
                }
            }
            return preparedWorkouts
        }

        private suspend fun prepareEnrichment(workout: WorkoutInput): PreparedWorkout {
            val sessionRecord =
                try {
                    client.readRecord(ExerciseSessionRecord::class, workout.id).record
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    logD("WorkoutEnrichmentRefresher") {
                        "SecurityException reading exercise session: ${e.message}"
                    }
                    null
                } catch (e: Exception) {
                    if (e.asHealthConnectSecurityCause() != null) {
                        logD("WorkoutEnrichmentRefresher") {
                            "Security cause reading exercise session: ${e.message}"
                        }
                        null
                    } else {
                        throw e
                    }
                }

            val sessionStart = Instant.ofEpochMilli(workout.startTime)
            val sessionEnd = Instant.ofEpochMilli(workout.endTime)
            val sessionOrigin = sessionRecord?.metadata?.dataOrigin?.packageName ?: workout.deviceName ?: ""

            val distanceOutcome =
                readIntervalTotal<DistanceRecord>(
                    sessionStart = sessionStart,
                    sessionEnd = sessionEnd,
                    sessionOrigin = sessionOrigin,
                ) { it.toIntervalTotal() }
            val elevationOutcome =
                readIntervalTotal<ElevationGainedRecord>(
                    sessionStart = sessionStart,
                    sessionEnd = sessionEnd,
                    sessionOrigin = sessionOrigin,
                ) { it.toIntervalTotal() }

            return PreparedWorkout(
                workout = workout,
                route = ReadOutcome.Denied, // Preserve existing route
                distanceMeters = distanceOutcome,
                elevationMeters = elevationOutcome,
            )
        }

        private suspend inline fun <reified T : Record> readIntervalTotal(
            sessionStart: Instant,
            sessionEnd: Instant,
            sessionOrigin: String,
            crossinline map: (T) -> DomainIntervalTotal,
        ): ReadOutcome<Float?> =
            try {
                val totals = mutableListOf<DomainIntervalTotal>()
                var pageToken: String? = null
                do {
                    val response =
                        client.readRecords(
                            ReadRecordsRequest(
                                recordType = T::class,
                                timeRangeFilter = TimeRangeFilter.between(sessionStart, sessionEnd),
                                pageToken = pageToken,
                            ),
                        )
                    totals += response.records.map { map(it) }
                    pageToken = response.pageToken
                } while (pageToken != null)

                val resolved =
                    SessionTotalsResolver.totalFor(
                        sessionStart = sessionStart,
                        sessionEnd = sessionEnd,
                        sessionOrigin = sessionOrigin,
                        totals = totals,
                    )
                ReadOutcome.Available(resolved?.toFloat())
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                logD("WorkoutEnrichmentRefresher") {
                    "Permission not granted for ${T::class.simpleName}: ${e.message}"
                }
                ReadOutcome.Denied
            } catch (e: Exception) {
                if (e.asHealthConnectSecurityCause() != null) {
                    ReadOutcome.Denied
                } else {
                    throw e
                }
            }

        companion object {
            private const val BATCH_SIZE = 50
        }
    }

private data class PartitionedIntervalChanges(
    val extents: List<Pair<Long, Long>>,
    val sourceUpserts: List<IntervalSourceRecord>,
    val sourceDeletes: List<String>,
)
