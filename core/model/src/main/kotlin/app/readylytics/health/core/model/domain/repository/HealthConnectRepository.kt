package app.readylytics.health.core.model.domain.repository

import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import java.time.Instant

class HealthConnectPermissionRevokedException(
    cause: SecurityException,
    val operation: String? = null,
    val recordType: String? = null,
) : Exception(
        buildString {
            append("Health Connect permission failure")
            operation?.let { append("; operation=$it") }
            recordType?.let { append("; recordType=$it") }
            cause.message?.takeIf { it.isNotBlank() }?.let { append("; cause=$it") }
        },
        cause,
    )

/**
 * Thrown when a Health Connect ingest window can't be read within its time budget (HC-002).
 * Deliberately *not* a [kotlinx.coroutines.CancellationException] subtype -- unlike the
 * [kotlinx.coroutines.TimeoutCancellationException] it's translated from at the `withTimeout` call
 * site, this must never be confused with cooperative cancellation by any layer above the read.
 */
class HealthConnectWindowTimeoutException(
    val windowStart: java.time.Instant,
    val windowEnd: java.time.Instant,
    cause: Throwable,
) : Exception(
        "Health Connect window read timed out: $windowStart..$windowEnd",
        cause,
    )

sealed interface PermissionStatus {
    data object Granted : PermissionStatus

    data object Unavailable : PermissionStatus

    data class Missing(
        val missing: Set<String>,
    ) : PermissionStatus
}

interface HealthConnectRepository : HealthConnectPermissionChecker {
    val criticalPermissions: Set<String>
    val requiredPermissions: Set<String>
    val optionalPermissions: Set<String>
    val allPermissions: Set<String>

    /**
     * Permission required for [PeriodicHealthSyncWorker][app.readylytics.health.workers.PeriodicHealthSyncWorker]
     * to read data while the app is backgrounded.
     */
    val backgroundReadPermission: String

    fun isAvailable(): Boolean

    fun isHistoryReadAvailable(): Boolean = false

    fun isBackgroundReadAvailable(): Boolean = false

    suspend fun checkPermissions(): PermissionStatus

    suspend fun readSleepSessions(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainSleepSessionRecord>>

    suspend fun readHeartRateSamples(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainHeartRateRecord>>

    suspend fun readHrvSamples(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainHrvRecord>>

    /**
     * Streams heart-rate samples page-by-page instead of materializing the whole [from]..[to]
     * range in memory (HC-001). [onPage] is invoked once per Health Connect page, in the order
     * pages are returned, passing the page of records and the next page token (R2-HC-002).
     */
    suspend fun readHeartRateSamplesPaged(
        from: Instant,
        to: Instant,
        startPageToken: String? = null,
        retryScope: ReadRetryScope? = null,
        onPage: suspend (records: List<DomainHeartRateRecord>, nextPageToken: String?) -> Unit,
    ): ReadOutcome<Unit>

    /** HRV equivalent of [readHeartRateSamplesPaged]. */
    suspend fun readHrvSamplesPaged(
        from: Instant,
        to: Instant,
        startPageToken: String? = null,
        retryScope: ReadRetryScope? = null,
        onPage: suspend (records: List<DomainHrvRecord>, nextPageToken: String?) -> Unit,
    ): ReadOutcome<Unit>

    /**
     * @param includeDetails when true, each session additionally costs one Health Connect
     * `readRecord` round-trip for its GPS route, plus two bulk reads per window for the
     * `DistanceRecord`/`ElevationGainedRecord` totals the recording app wrote alongside the
     * session. Callers that only need session metadata (device discovery, counts) must pass false
     * -- otherwise a wide window turns into thousands of sequential IPC calls.
     */
    suspend fun readExerciseSessions(
        from: Instant,
        to: Instant,
        includeDetails: Boolean = true,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainExerciseSessionRecord>>

    suspend fun readStepsRecords(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainStepsRecord>>

    /**
     * Streams steps records page-by-page instead of materializing the whole [from]..[to] range in
     * memory (HC-001) -- a day of continuously-recorded steps can reach tens of thousands of rows.
     * [onPage] is invoked once per Health Connect page, in the order pages are returned.
     */
    suspend fun readStepsRecordsPaged(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
        onPage: suspend (records: List<DomainStepsRecord>) -> Unit,
    ): ReadOutcome<Unit>

    suspend fun readSteps(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<Long>

    /**
     * Daily step totals for [from]..[to], grouped by local calendar day in [zoneId] via Health
     * Connect's `aggregateGroupByPeriod` (HC-003) -- one grouped call per range instead of one
     * `readSteps` aggregate call per day. Falls back to the per-day aggregate only if the provider
     * doesn't support grouped-by-period aggregation.
     */
    suspend fun readDailyStepTotals(
        from: Instant,
        to: Instant,
        zoneId: java.time.ZoneId,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<Map<java.time.LocalDate, Long>>

    suspend fun discoverDevices(windowDays: Int = 2): List<String>

    suspend fun readWeightRecords(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainWeightRecord>>

    suspend fun readBodyFatRecords(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainBodyFatRecord>>

    suspend fun readBloodPressureRecords(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainBloodPressureRecord>>

    suspend fun readOxygenSaturationRecords(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainOxygenSaturationRecord>>

    suspend fun readBodyTemperatureRecords(
        from: Instant,
        to: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainBodyTemperatureRecord>>

    suspend fun readVo2MaxRecords(
        startTime: Instant,
        endTime: Instant,
        retryScope: ReadRetryScope? = null,
    ): ReadOutcome<List<DomainVo2MaxRecord>>

    /** Reads a single exercise session by ID with its route data. */
    suspend fun readExerciseSession(id: String): DomainExerciseSessionRecord?
}
