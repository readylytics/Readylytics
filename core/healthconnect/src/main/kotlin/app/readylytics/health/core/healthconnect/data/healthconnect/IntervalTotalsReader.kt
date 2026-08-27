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
import app.readylytics.health.core.model.domain.util.SessionTotalsResolver
import app.readylytics.health.core.model.domain.util.logD
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

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
    ) {
        internal var clientOverride: HealthConnectClient? = null

        private val client: HealthConnectClient
            get() = clientOverride ?: HealthConnectClient.getOrCreate(context)

        suspend fun readDistanceTotals(
            from: Instant,
            to: Instant,
        ): List<DomainIntervalTotal> =
            readIntervalTotals(DistanceRecord::class, from, to) { it.toIntervalTotal() }

        suspend fun readElevationTotals(
            from: Instant,
            to: Instant,
        ): List<DomainIntervalTotal> =
            readIntervalTotals(ElevationGainedRecord::class, from, to) { it.toIntervalTotal() }

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
         * Bulk-reads an interval record type, degrading to an empty list when its (optional)
         * permission is not granted -- distance and elevation are enrichment, never a reason to
         * fail an exercise sync pass.
         */
        private suspend fun <T : Record> readIntervalTotals(
            recordType: KClass<T>,
            from: Instant,
            to: Instant,
            map: (T) -> DomainIntervalTotal,
        ): List<DomainIntervalTotal> =
            withContext(ioDispatcher) {
                try {
                    readAllPages(recordType, from, to).map(map)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthConnectPermissionRevokedException) {
                    logD("IntervalTotalsReader") {
                        "${recordType.simpleName} permission not granted; " +
                            "falling back to route-derived totals (${e.message})"
                    }
                    emptyList()
                } catch (e: Exception) {
                    if (e.asHealthConnectSecurityCause() == null) throw e
                    logD("IntervalTotalsReader") {
                        "${recordType.simpleName} permission not granted; falling back to route-derived totals"
                    }
                    emptyList()
                }
            }

        private suspend fun <T : Record> readAllPages(
            recordType: KClass<T>,
            from: Instant,
            to: Instant,
        ): List<T> {
            val all = mutableListOf<T>()
            var pageToken: String? = null
            try {
                do {
                    @Suppress("UNCHECKED_CAST")
                    val response =
                        client.readRecords(
                            ReadRecordsRequest(
                                recordType = recordType,
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
                rethrowReadFailureOrOriginal(recordType.simpleName, e)
            }
            return all
        }
    }
