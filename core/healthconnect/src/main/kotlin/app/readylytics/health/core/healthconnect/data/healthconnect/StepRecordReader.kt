package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import app.readylytics.health.core.healthconnect.domain.sync.retryWithBackoff
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

import androidx.health.connect.client.permission.HealthPermission
import app.readylytics.health.core.model.domain.repository.ReadOutcome

/**
 * Reads and aggregates step records from Health Connect.
 */
@Singleton
class StepRecordReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val client: HealthConnectClient,
    ) {
        private fun isSdkAvailable(): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        private suspend fun hasStepsPermission(): Boolean =
            if (!isSdkAvailable()) {
                false
            } else {
                try {
                    client.permissionController
                        .getGrantedPermissions()
                        .contains(HealthPermission.getReadPermission(StepsRecord::class))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }

        suspend fun readStepsRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
        ): ReadOutcome<List<DomainStepsRecord>> =
            readStepsOutcome {
                val all = mutableListOf<StepsRecord>()
                readStepsRecordsPagesStreaming(from, to, retryScope) { all.addAll(it) }
                all.map { it.toDomain() }
            }

        /** Streams pages; completion is reported only after the final provider page. */
        suspend fun readStepsRecordsPaged(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
            onPage: suspend (List<DomainStepsRecord>) -> Unit,
        ): ReadOutcome<Unit> = readStepsOutcome {
            readStepsRecordsPagesStreaming(from, to, retryScope) { onPage(it.map { record -> record.toDomain() }) }
        }

        private suspend fun <T> readStepsOutcome(read: suspend () -> T): ReadOutcome<T> =
            withContext(ioDispatcher) {
                if (!isSdkAvailable()) return@withContext ReadOutcome.Unsupported
                if (!hasStepsPermission()) return@withContext ReadOutcome.Denied
                try {
                    ReadOutcome.Available(read())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthConnectPermissionRevokedException) {
                    logD("StepRecordReader") { "Steps records permission revoked: ${e.message}" }
                    ReadOutcome.Denied
                } catch (e: SecurityException) {
                    logD("StepRecordReader") { "Steps records permission denied: ${e.message}" }
                    ReadOutcome.Denied
                } catch (e: Exception) {
                    val securityCause = e.asHealthConnectSecurityCause()
                    if (securityCause != null) {
                        logD("StepRecordReader") { "Steps records permission denied: ${securityCause.message}" }
                        ReadOutcome.Denied
                    } else {
                        throw e
                    }
                }
            }

        suspend fun readSteps(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
        ): ReadOutcome<Long> =
            withContext(ioDispatcher) {
                if (!isSdkAvailable()) return@withContext ReadOutcome.Unsupported
                if (!hasStepsPermission()) return@withContext ReadOutcome.Denied
                try {
                    val result =
                        retryWithBackoff(budget = retryScope) {
                            client.aggregate(
                                AggregateRequest(
                                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                                    timeRangeFilter = TimeRangeFilter.between(from, to),
                                ),
                            )
                        }
                    ReadOutcome.Available(result[StepsRecord.COUNT_TOTAL] ?: 0L)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HealthConnectPermissionRevokedException) {
                    logD("StepRecordReader") { "Steps aggregate permission revoked: ${e.message}" }
                    ReadOutcome.Denied
                } catch (e: SecurityException) {
                    logD("StepRecordReader") { "Steps aggregate permission denied: ${e.message}" }
                    ReadOutcome.Denied
                } catch (e: Exception) {
                    val securityCause = e.asHealthConnectSecurityCause()
                    if (securityCause != null) {
                        logD("StepRecordReader") { "Steps aggregate permission denied: ${securityCause.message}" }
                        ReadOutcome.Denied
                    } else {
                        throw e
                    }
                }
            }

        suspend fun readDailyStepTotals(
            from: Instant,
            to: Instant,
            zoneId: ZoneId,
            retryScope: ReadRetryScope? = null,
        ): ReadOutcome<Map<LocalDate, Long>> =
            withContext(ioDispatcher) {
                if (!isSdkAvailable()) return@withContext ReadOutcome.Unsupported
                if (!hasStepsPermission()) return@withContext ReadOutcome.Denied
                try {
                    val response =
                        retryWithBackoff(budget = retryScope) {
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
                        }
                    val mapped =
                        response
                            .mapNotNull { group ->
                                val total = group.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
                                group.startTime.toLocalDate() to total
                            }.toMap()
                    ReadOutcome.Available(mapped)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnsupportedOperationException) {
                    // HC-003: defensive fallback -- if a provider doesn't support grouped-by-period
                    // aggregation, fall back to one per-day aggregate call. Slower, but correct.
                    logD("StepRecordReader") {
                        "aggregateGroupByPeriod unsupported; falling back to per-day step aggregate (${e.message})"
                    }
                    readDailyStepTotalsPerDay(from, to, zoneId, retryScope)
                } catch (e: HealthConnectPermissionRevokedException) {
                    logD("StepRecordReader") { "Daily step totals permission revoked: ${e.message}" }
                    ReadOutcome.Denied
                } catch (e: SecurityException) {
                    logD("StepRecordReader") { "Daily step totals permission denied: ${e.message}" }
                    ReadOutcome.Denied
                } catch (e: Exception) {
                    val securityCause = e.asHealthConnectSecurityCause()
                    if (securityCause != null) {
                        logD("StepRecordReader") { "Daily step totals permission denied: ${securityCause.message}" }
                        ReadOutcome.Denied
                    } else {
                        throw e
                    }
                }
            }

        private suspend fun readDailyStepTotalsPerDay(
            from: Instant,
            to: Instant,
            zoneId: ZoneId,
            retryScope: ReadRetryScope? = null,
        ): ReadOutcome<Map<LocalDate, Long>> {
            val totals = mutableMapOf<LocalDate, Long>()
            var day = LocalDateTime.ofInstant(from, zoneId).toLocalDate()
            val endDay = LocalDateTime.ofInstant(to, zoneId).toLocalDate()
            var failureOutcome: ReadOutcome<Nothing>? = null
            while (!day.isAfter(endDay) && failureOutcome == null) {
                val dayStart = day.atStartOfDay(zoneId).toInstant()
                val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant()
                val boundedStart = maxOf(dayStart, from)
                val boundedEnd = minOf(dayEnd, to)
                if (boundedStart.isBefore(boundedEnd)) {
                    when (val outcome = readSteps(boundedStart, boundedEnd, retryScope)) {
                        is ReadOutcome.Available -> totals[day] = outcome.data
                        ReadOutcome.Denied -> failureOutcome = ReadOutcome.Denied
                        ReadOutcome.Unsupported -> failureOutcome = ReadOutcome.Unsupported
                    }
                }
                day = day.plusDays(1)
            }
            return failureOutcome ?: ReadOutcome.Available(totals)
        }

        /**
         * Pages through every `StepsRecord` in [from]..[to], invoking [onPage] once per Health
         * Connect page so neither caller ever holds more than one page in memory at a time.
         * [retryScope] (Task 1) wraps each individual SDK page fetch.
         */
        private suspend fun readStepsRecordsPagesStreaming(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope? = null,
            onPage: suspend (List<StepsRecord>) -> Unit,
        ) {
            var pageToken: String? = null
            try {
                do {
                    val response =
                        retryWithBackoff(budget = retryScope) {
                            client.readRecords(
                                ReadRecordsRequest(
                                    recordType = StepsRecord::class,
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
                rethrowReadFailureOrOriginal(StepsRecord::class.simpleName, e)
            }
        }
    }
