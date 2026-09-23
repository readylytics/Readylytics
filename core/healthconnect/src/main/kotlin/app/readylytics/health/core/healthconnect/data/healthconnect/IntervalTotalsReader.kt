package app.readylytics.health.core.healthconnect.data.healthconnect

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
        ): ReadOutcome<List<DomainIntervalTotal>> =
            readIntervalTotals<DistanceRecord>(from, to) { it.toIntervalTotal() }

        suspend fun readElevationTotals(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainIntervalTotal>> =
            readIntervalTotals<ElevationGainedRecord>(from, to) { it.toIntervalTotal() }

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
         * Bulk-reads an interval record type, returning Denied/Unsupported when permission is not
         * granted or SDK is unavailable, or transient exceptions propagating.
         */
        private suspend inline fun <reified T : Record> readIntervalTotals(
            from: Instant,
            to: Instant,
            noinline map: (T) -> DomainIntervalTotal,
        ): ReadOutcome<List<DomainIntervalTotal>> =
            withContext(ioDispatcher) {
                val isSdkAvailable =
                    HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
                if (!isSdkAvailable) {
                    return@withContext ReadOutcome.Unsupported
                }
                try {
                    ReadOutcome.Available(
                        readAllPages<T>(from, to).map(map),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthConnectPermissionRevokedException) {
                    logD("IntervalTotalsReader") {
                        "${T::class.simpleName} permission not granted; " +
                            "falling back to route-derived totals (${e.message})"
                    }
                    ReadOutcome.Denied
                } catch (e: SecurityException) {
                    logD("IntervalTotalsReader") {
                        "${T::class.simpleName} permission not granted; " +
                            "falling back to route-derived totals (${e.message})"
                    }
                    ReadOutcome.Denied
                } catch (e: Exception) {
                    val securityCause = e.asHealthConnectSecurityCause()
                    if (securityCause != null) {
                        logD("IntervalTotalsReader") {
                            "${T::class.simpleName} permission not granted; " +
                                "falling back to route-derived totals (${securityCause.message})"
                        }
                        ReadOutcome.Denied
                    } else {
                        throw e
                    }
                }
            }

        private suspend inline fun <reified T : Record> readAllPages(
            from: Instant,
            to: Instant,
        ): List<T> {
            val all = mutableListOf<T>()
            var pageToken: String? = null
            try {
                do {
                    val response =
                        client.readRecords(
                            ReadRecordsRequest(
                                recordType = T::class,
                                timeRangeFilter = TimeRangeFilter.between(from, to),
                                pageToken = pageToken,
                            ),
                        )
                    all.addAll(response.records)
                    pageToken = response.pageToken
                } while (pageToken != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rethrowReadFailureOrOriginal(T::class.simpleName, e)
            }
            return all
        }
    }
