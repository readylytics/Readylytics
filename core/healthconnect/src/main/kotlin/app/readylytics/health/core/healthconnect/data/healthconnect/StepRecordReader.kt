package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.util.logD
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and aggregates step records from Health Connect.
 */
@Singleton
class StepRecordReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        internal var clientOverride: HealthConnectClient? = null

        private val client: HealthConnectClient
            get() = clientOverride ?: HealthConnectClient.getOrCreate(context)

        suspend fun readStepsRecords(
            from: Instant,
            to: Instant,
        ): List<DomainStepsRecord> =
            withContext(ioDispatcher) {
                try {
                    readAllStepsRecordsPages(from, to).map { it.toDomain() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.asHealthConnectSecurityCause() == null) throw e
                    logD("StepRecordReader") {
                        "Steps record permission not granted"
                    }
                    emptyList()
                }
            }

        suspend fun readSteps(
            from: Instant,
            to: Instant,
        ): Long =
            withContext(ioDispatcher) {
                try {
                    val result =
                        client.aggregate(
                            AggregateRequest(
                                metrics = setOf(StepsRecord.COUNT_TOTAL),
                                timeRangeFilter = TimeRangeFilter.between(from, to),
                            ),
                        )
                    result[StepsRecord.COUNT_TOTAL] ?: 0L
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.asHealthConnectSecurityCause() == null) throw e
                    logD("StepRecordReader") {
                        "Steps permission not granted"
                    }
                    0L
                }
            }

        suspend fun readDailyStepTotals(
            from: Instant,
            to: Instant,
            zoneId: ZoneId,
        ): Map<LocalDate, Long> =
            withContext(ioDispatcher) {
                try {
                    val response =
                        client.aggregateGroupByPeriod(
                            AggregateGroupByPeriodRequest(
                                metrics = setOf(StepsRecord.COUNT_TOTAL),
                                timeRangeFilter =
                                    TimeRangeFilter.between(
                                        LocalDateTime.ofInstant(from, zoneId),
                                        LocalDateTime.ofInstant(to, zoneId),
                                    ),
                                timeRangeSlicer = Period.ofDays(1),
                            ),
                        )
                    response
                        .mapNotNull { group ->
                            val total = group.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
                            group.startTime.toLocalDate() to total
                        }.toMap()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedOperationException) {
                    // HC-003: defensive fallback -- if a provider doesn't support grouped-by-period
                    // aggregation, fall back to one per-day aggregate call. Slower, but correct.
                    logD("StepRecordReader") {
                        "aggregateGroupByPeriod unsupported; falling back to per-day step aggregate (${e.message})"
                    }
                    readDailyStepTotalsPerDay(from, to, zoneId)
                } catch (e: Exception) {
                    if (e.asHealthConnectSecurityCause() == null) throw e
                    logD("StepRecordReader") {
                        "Steps permission not granted"
                    }
                    emptyMap()
                }
            }

        private suspend fun readDailyStepTotalsPerDay(
            from: Instant,
            to: Instant,
            zoneId: ZoneId,
        ): Map<LocalDate, Long> {
            val totals = mutableMapOf<LocalDate, Long>()
            var day = LocalDateTime.ofInstant(from, zoneId).toLocalDate()
            val endDay = LocalDateTime.ofInstant(to, zoneId).toLocalDate()
            while (!day.isAfter(endDay)) {
                val dayStart = day.atStartOfDay(zoneId).toInstant()
                val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                val boundedStart = maxOf(dayStart, from)
                val boundedEnd = minOf(dayEnd, to)
                if (boundedStart.isBefore(boundedEnd)) {
                    totals[day] = readSteps(boundedStart, boundedEnd)
                }
                day = day.plusDays(1)
            }
            return totals
        }

        private suspend fun readAllStepsRecordsPages(
            from: Instant,
            to: Instant,
        ): List<StepsRecord> {
            val all = mutableListOf<StepsRecord>()
            var pageToken: String? = null
            try {
                do {
                    val response =
                        client.readRecords(
                            ReadRecordsRequest(
                                recordType = StepsRecord::class,
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
                rethrowReadFailureOrOriginal(StepsRecord::class.simpleName, e)
            }
            return all
        }
    }
