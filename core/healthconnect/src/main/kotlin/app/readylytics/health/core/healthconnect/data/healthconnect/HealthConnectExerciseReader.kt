package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import app.readylytics.health.core.healthconnect.domain.sync.retryWithBackoff
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.repository.ExerciseSessionRead
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import app.readylytics.health.core.model.domain.repository.StoredWorkoutRouteSnapshot
import app.readylytics.health.core.model.domain.repository.WorkoutRouteLookup
import app.readylytics.health.core.model.domain.repository.valueOrPrevious
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Health Connect exercise sessions: bulk reads (optionally enriched with interval totals and
 * routes) and the single-session lookup used for route detail.
 */
@Singleton
class HealthConnectExerciseReader
    @Inject
    constructor(
        private val client: HealthConnectClient,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val availability: HealthConnectAvailability,
        private val permissions: HealthConnectPermissionGateway,
        private val pagedReader: HealthConnectPagedReader,
        private val guard: HealthConnectReadGuard,
        private val intervalTotalsReader: IntervalTotalsReader,
        private val routeLookup: WorkoutRouteLookup,
    ) {
        suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
            includeDetails: Boolean,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainExerciseSessionRecord>> =
            readExerciseSessionsResult(from, to, includeDetails, retryScope).sessions

        suspend fun readExerciseSessionsWithCompletion(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ExerciseSessionRead = readExerciseSessionsResult(from, to, true, retryScope, scanEmptyWindow = true)

        private suspend fun readExerciseSessionsResult(
            from: Instant,
            to: Instant,
            includeDetails: Boolean,
            retryScope: ReadRetryScope?,
            scanEmptyWindow: Boolean = false,
        ): ExerciseSessionRead {
            val completed = mutableSetOf<String>()
            val outcome =
                guard.guardedRead(ExerciseSessionRecord::class, LABEL_EXERCISE) {
                    val sessions = pagedReader.readAll<ExerciseSessionRecord>(from, to, retryScope)
                    if ((!scanEmptyWindow && sessions.isEmpty()) || !includeDetails) {
                        sessions.map { it.toDomain(null) }
                    } else {
                        val totals = intervalTotalsReader.readSessionTotals(sessions, from, to, retryScope)
                        completed.addAll(totals.completedTypes)

                        val routeConsent = WorkoutRouteReadPolicy.hasConsent(client)
                        val storedRoutes = routeLookup.snapshots(sessions.map { it.metadata.id })
                        sessions.map { session ->
                            session.toDomain(
                                routeResult = readRouteResult(session, routeConsent, storedRoutes, retryScope),
                                totalDistanceMeters = totals.distance[session.metadata.id],
                                elevationGainMeters = totals.elevation[session.metadata.id],
                            )
                        }
                    }
                }
            return ExerciseSessionRead(outcome, if (outcome is ReadOutcome.Available) completed.toSet() else emptySet())
        }

        /** Reads a route only when consent and the stored identity require enrichment. */
        private suspend fun readRouteResult(
            session: ExerciseSessionRecord,
            routeConsent: Boolean,
            storedRoutes: Map<String, StoredWorkoutRouteSnapshot>,
            retryScope: ReadRetryScope?,
        ): ExerciseRouteResult =
            if (!routeConsent ||
                !WorkoutRouteReadPolicy.needsRead(session, storedRoutes[session.metadata.id])
            ) {
                ExerciseRouteResult.ConsentRequired()
            } else try {
                val record =
                    retryWithBackoff(budget = retryScope) {
                        client.readRecord(ExerciseSessionRecord::class, session.metadata.id).record
                    }
                record.exerciseRouteResult
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                logW("HealthConnectRepository") {
                    "Exercise route permission not granted: ${e.message}; omitting route"
                }
                ExerciseRouteResult.ConsentRequired()
            } catch (e: Exception) {
                if (e.asHealthConnectSecurityCause() != null) {
                    logW("HealthConnectRepository") {
                        "Exercise route permission not granted; omitting route"
                    }
                    ExerciseRouteResult.ConsentRequired()
                } else {
                    // A transient/unexpected failure must propagate for retry, never
                    // be coerced into "no route" -- that would silently strip route
                    // data from a session that genuinely has it (H5/WP-09).
                    throw e
                }
            }

        suspend fun readExerciseSession(id: String): DomainExerciseSessionRecord? =
            withContext(ioDispatcher) {
                if (!availability.isAvailable()) return@withContext null
                if (!permissions.hasReadPermission(ExerciseSessionRecord::class, LABEL_EXERCISE)) {
                    return@withContext null
                }
                try {
                    val record = client.readRecord(ExerciseSessionRecord::class, id).record
                    val routeResult = record.exerciseRouteResult
                    logD("HealthConnectRepository") {
                        "Read single exercise session $id (${record.exerciseType}) " +
                            "route result: ${routeResult.javaClass.simpleName}"
                    }
                    val distanceTotals =
                        intervalTotalsReader.readDistanceTotals(record.startTime, record.endTime)
                            .valueOrPrevious(emptyList())
                    val elevationTotals =
                        intervalTotalsReader.readElevationTotals(record.startTime, record.endTime)
                            .valueOrPrevious(emptyList())
                    record.toDomain(
                        routeResult = routeResult,
                        totalDistanceMeters = intervalTotalsReader.resolveTotal(record, distanceTotals),
                        elevationGainMeters = intervalTotalsReader.resolveTotal(record, elevationTotals),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    logW("HealthConnectRepository") {
                        "SecurityException reading exercise session $id: ${e.message}"
                    }
                    null
                } catch (e: Exception) {
                    logE("HealthConnectRepository", e) {
                        "Failed to read exercise session $id"
                    }
                    null
                }
            }

        private companion object {
            const val LABEL_EXERCISE = "Exercise session"
        }
    }
