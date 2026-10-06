package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import app.readylytics.health.core.healthconnect.domain.sync.retryWithBackoff
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.model.DomainIntervalTotal
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.util.SessionTotalsResolver
import app.readylytics.health.core.model.domain.util.logD
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads interval records (such as distance and elevation gain) from Health Connect
 * and resolves totals for exercise sessions.
 */
@Singleton
class IntervalTotalsReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val client: HealthConnectClient,
    ) {
        suspend fun readDistanceTotals(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
        ): ReadOutcome<List<DomainIntervalTotal>> =
            readIntervalTotals<DistanceRecord>(from, to, retryScope) { it.toIntervalTotal() }

        suspend fun readElevationTotals(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
        ): ReadOutcome<List<DomainIntervalTotal>> =
            readIntervalTotals<ElevationGainedRecord>(from, to, retryScope) { it.toIntervalTotal() }

        /**
         * Streams distance totals page-by-page instead of materializing the whole [from]..[to]
         * range in memory (HC-001) -- a phone writing continuous distance can be as dense as steps.
         * [onPage] is invoked once per Health Connect page; callers fold each page into their own
         * per-session running totals (see [HealthConnectRepositoryImpl.readExerciseSessions]).
         */
        suspend fun readDistanceTotalsPaged(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
            onPage: suspend (List<DomainIntervalTotal>) -> Unit,
        ): ReadOutcome<Unit> =
            readIntervalTotalsPaged<DistanceRecord>(from, to, retryScope, onPage) { it.toIntervalTotal() }

        /** Elevation-gain equivalent of [readDistanceTotalsPaged]. */
        suspend fun readElevationTotalsPaged(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
            onPage: suspend (List<DomainIntervalTotal>) -> Unit,
        ): ReadOutcome<Unit> =
            readIntervalTotalsPaged<ElevationGainedRecord>(from, to, retryScope, onPage) { it.toIntervalTotal() }

        fun resolveTotal(
            session: ExerciseSessionRecord,
            totals: List<DomainIntervalTotal>,
        ): Double? =
            SessionTotalsResolver.totalFor(
                sessionStart = session.startTime,
                sessionEnd = session.endTime,
                sessionOrigin = session.metadata.dataOrigin.packageName,
                totals = totals,
            )

        /**
         * Streaming counterpart of [resolveTotal]: folds one page of interval totals into
         * [accumulator] (keyed by session id) for every session in [sessions], so attribution never
         * requires holding the full [from]..[to] totals list -- only one page plus the bounded
         * session-id accumulator map.
         */
        fun foldPageIntoSessionTotals(
            sessions: List<ExerciseSessionRecord>,
            page: List<DomainIntervalTotal>,
            accumulator: MutableMap<String, Double?>,
        ) {
            if (page.isEmpty()) return
            for (session in sessions) {
                accumulator[session.metadata.id] =
                    SessionTotalsResolver.accumulate(
                        sessionStart = session.startTime,
                        sessionEnd = session.endTime,
                        sessionOrigin = session.metadata.dataOrigin.packageName,
                        totalsPage = page,
                        runningTotal = accumulator[session.metadata.id],
                    )
            }
        }

        /**
         * Bulk-reads an interval record type, returning Denied/Unsupported when permission is not
         * granted or SDK is unavailable, or transient exceptions propagating.
         */
        private suspend inline fun <reified T : Record> readIntervalTotals(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
            noinline map: (T) -> DomainIntervalTotal,
        ): ReadOutcome<List<DomainIntervalTotal>> =
            readIntervalOutcome(T::class.simpleName) {
                val all = mutableListOf<DomainIntervalTotal>()
                readAllPagesStreaming<T>(from, to, retryScope) { page -> all.addAll(page.map(map)) }
                all
            }

        private suspend fun <R> readIntervalOutcome(recordName: String?, read: suspend () -> R): ReadOutcome<R> =
            withContext(ioDispatcher) {
                val isSdkAvailable =
                    HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
                if (!isSdkAvailable) {
                    return@withContext ReadOutcome.Unsupported
                }
                try {
                    ReadOutcome.Available(read())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthConnectPermissionRevokedException) {
                    logD("IntervalTotalsReader") {
                        "$recordName permission not granted; " +
                            "falling back to route-derived totals (${e.message})"
                    }
                    ReadOutcome.Denied
                } catch (e: SecurityException) {
                    logD("IntervalTotalsReader") {
                        "$recordName permission not granted; " +
                            "falling back to route-derived totals (${e.message})"
                    }
                    ReadOutcome.Denied
                } catch (e: Exception) {
                    val securityCause = e.asHealthConnectSecurityCause()
                    if (securityCause != null) {
                        logD("IntervalTotalsReader") {
                            "$recordName permission not granted; " +
                                "falling back to route-derived totals (${securityCause.message})"
                        }
                        ReadOutcome.Denied
                    } else {
                        throw e
                    }
                }
            }

        /**
         * Paged provider read: invokes [onPage] once per Health Connect page instead of
         * accumulating every page into one list (HC-001). [retryScope] (Task 1) wraps each page.
         */
        private suspend inline fun <reified T : Record> readAllPagesStreaming(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
            onPage: suspend (List<T>) -> Unit,
        ) {
            var pageToken: String? = null
            try {
                do {
                    val response =
                        retryWithBackoff(budget = retryScope) {
                            client.readRecords(
                                ReadRecordsRequest(
                                    recordType = T::class,
                                    timeRangeFilter = TimeRangeFilter.between(from, to),
                                    pageToken = pageToken,
                                ),
                            )
                        }
                    onPage(response.records)
                    pageToken = response.pageToken
                } while (pageToken != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rethrowReadFailureOrOriginal(T::class.simpleName, e)
            }
        }

        /**
         * Paged counterpart of [readIntervalTotals]: streams pages instead of materializing the
         * whole range, returning Denied/Unsupported identically.
         */
        private suspend inline fun <reified T : Record> readIntervalTotalsPaged(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
            noinline onPage: suspend (List<DomainIntervalTotal>) -> Unit,
            noinline map: (T) -> DomainIntervalTotal,
        ): ReadOutcome<Unit> =
            readIntervalOutcome(T::class.simpleName) {
                readAllPagesStreaming<T>(from, to, retryScope) { page -> onPage(page.map(map)) }
            }
    }
