package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.DomainBloodPressureRecord
import app.readylytics.health.domain.model.DomainBodyFatRecord
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainStepsRecord
import app.readylytics.health.domain.model.DomainWeightRecord
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

interface HealthConnectRepository {
    val criticalPermissions: Set<String>
    val requiredPermissions: Set<String>
    val optionalPermissions: Set<String>
    val allPermissions: Set<String>

    /** Permission required for [PeriodicHealthSyncWorker][app.readylytics.health.workers.PeriodicHealthSyncWorker] to read data while the app is backgrounded. */
    val backgroundReadPermission: String

    fun isAvailable(): Boolean

    suspend fun checkPermissions(): PermissionStatus

    suspend fun readSleepSessions(
        from: Instant,
        to: Instant,
    ): List<DomainSleepSessionRecord>

    suspend fun readHeartRateSamples(
        from: Instant,
        to: Instant,
    ): List<DomainHeartRateRecord>

    suspend fun readHrvSamples(
        from: Instant,
        to: Instant,
    ): List<DomainHrvRecord>

    /**
     * Streams heart-rate samples page-by-page instead of materializing the whole [from]..[to]
     * range in memory (HC-001). [onPage] is invoked once per Health Connect page, in the order
     * pages are returned; pages are not guaranteed to be globally sorted across page boundaries,
     * only within Health Connect's own per-page ordering.
     */
    suspend fun readHeartRateSamplesPaged(
        from: Instant,
        to: Instant,
        onPage: suspend (List<DomainHeartRateRecord>) -> Unit,
    )

    /** HRV equivalent of [readHeartRateSamplesPaged]. */
    suspend fun readHrvSamplesPaged(
        from: Instant,
        to: Instant,
        onPage: suspend (List<DomainHrvRecord>) -> Unit,
    )

    suspend fun readExerciseSessions(
        from: Instant,
        to: Instant,
    ): List<DomainExerciseSessionRecord>

    suspend fun readStepsRecords(
        from: Instant,
        to: Instant,
    ): List<DomainStepsRecord>

    suspend fun readSteps(
        from: Instant,
        to: Instant,
    ): Long

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
    ): Map<java.time.LocalDate, Long>

    suspend fun discoverDevices(windowDays: Int = 2): List<String>

    suspend fun readWeightRecords(
        from: Instant,
        to: Instant,
    ): List<DomainWeightRecord>

    suspend fun readBodyFatRecords(
        from: Instant,
        to: Instant,
    ): List<DomainBodyFatRecord>

    suspend fun readBloodPressureRecords(
        from: Instant,
        to: Instant,
    ): List<DomainBloodPressureRecord>

    suspend fun readOxygenSaturationRecords(
        from: Instant,
        to: Instant,
    ): List<DomainOxygenSaturationRecord>
}
