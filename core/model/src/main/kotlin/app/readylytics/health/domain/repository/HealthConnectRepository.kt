package app.readylytics.health.domain.repository

import app.readylytics.health.domain.model.DomainBloodPressureRecord
import app.readylytics.health.domain.model.DomainBodyFatRecord
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainExerciseRoute
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainStepsRecord
import app.readylytics.health.domain.model.DomainWeightRecord
import java.time.Instant

class HealthConnectPermissionRevokedException(
    cause: SecurityException,
) : Exception("Health Connect permissions were revoked", cause)

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

    suspend fun readStepsRange(
        from: Instant,
        to: Instant,
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

    suspend fun readExerciseRoute(sessionId: String): DomainExerciseRoute?
}
