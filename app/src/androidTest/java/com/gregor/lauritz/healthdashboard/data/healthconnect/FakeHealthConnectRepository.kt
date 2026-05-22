package com.gregor.lauritz.healthdashboard.data.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectPermissionRevokedException
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Identifiers for the read operations exposed by [HealthConnectRepository]. Used by
 * [FakeHealthConnectRepository] to route synthetic errors to specific read paths.
 */
internal enum class FakeOp {
    Sleep,
    HeartRate,
    RestingHeartRate,
    Hrv,
    Exercise,
    Steps,
    Weight,
    BodyFat,
    BloodPressure,
    Discovery,
}

/**
 * In-memory fake of [HealthConnectRepository] used by androidTest integration suites.
 *
 * The real [HealthConnectRepositoryImpl] cannot be exercised in isolation because
 * [androidx.health.connect.client.HealthConnectClient] requires a real provider on the
 * device and its record classes are final (so dexmaker-based mocking cannot fabricate
 * them). This fake mirrors the same observable behaviour as the production impl:
 *   - critical reads translate [SecurityException] -> [HealthConnectPermissionRevokedException]
 *   - optional reads (weight, body fat, blood pressure) swallow exceptions and return empty
 *   - reads accept an [Instant] range and filter inclusively at the low bound, exclusively
 *     at the high bound (matching [androidx.health.connect.client.time.TimeRangeFilter.between])
 *   - readSteps aggregates totals; readStepsRange groups by [LocalDate]
 *   - paginated reads loop until exhausted; the fake records the page count served
 *
 * Tests populate the public counters (e.g. [sleepCount]) and the [errors] map to drive
 * the desired scenario, then assert on the values returned through the interface.
 */
internal class FakeHealthConnectRepository : HealthConnectRepository {
    override val criticalPermissions: Set<String> =
        setOf(
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )

    override val requiredPermissions: Set<String> =
        criticalPermissions + setOf("android.permission.health.READ_HEALTH_DATA_HISTORY")

    override val optionalPermissions: Set<String> =
        setOf(
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
            HealthPermission.getReadPermission(BloodPressureRecord::class),
        )

    override val allPermissions: Set<String> = requiredPermissions + optionalPermissions

    // ---- state knobs ----

    var sdkAvailable: Boolean = true
    var granted: Set<String> = emptySet()
    var permissionError: Throwable? = null
    val errors: MutableMap<FakeOp, Throwable> = mutableMapOf()

    val sleepCount: MutableMap<Instant, Int> = mutableMapOf()
    val hrCount: MutableMap<Instant, Int> = mutableMapOf()
    val rhrCount: MutableMap<Instant, Int> = mutableMapOf()
    val hrvCount: MutableMap<Instant, Int> = mutableMapOf()
    val exerciseCount: MutableMap<Instant, Int> = mutableMapOf()
    val weightCount: MutableMap<Instant, Int> = mutableMapOf()
    val bodyFatCount: MutableMap<Instant, Int> = mutableMapOf()
    val bpCount: MutableMap<Instant, Int> = mutableMapOf()

    /** Synthetic steps keyed by sample timestamp. Each entry is one StepsRecord. */
    val stepsByInstant: MutableMap<Instant, Long> = mutableMapOf()

    /** Devices the fake should surface for the current discovery window. */
    var devicesInWindow: List<String> = emptyList()

    var lastDiscoveryWindowDays: Int = -1

    // ---- page counters (to verify pagination loop terminates) ----
    var sleepPagesServed: Int = 0
    var hrPagesServed: Int = 0
    var exercisePagesServed: Int = 0

    private val pageSize: Int = 50

    // ---- impl ----

    override fun isAvailable(): Boolean = sdkAvailable

    override suspend fun checkPermissions(): PermissionStatus {
        if (!isAvailable()) return PermissionStatus.Unavailable
        permissionError?.let { throw it }
        return if (granted.containsAll(requiredPermissions)) {
            PermissionStatus.Granted
        } else {
            PermissionStatus.Missing(requiredPermissions - granted)
        }
    }

    override suspend fun readSleepSessions(
        from: Instant,
        to: Instant,
    ): List<SleepSessionRecord> {
        translateCritical(FakeOp.Sleep)
        val total = totalInRange(sleepCount, from, to)
        sleepPagesServed = pagesFor(total)
        return stubList(total)
    }

    override suspend fun readHeartRateSamples(
        from: Instant,
        to: Instant,
    ): List<HeartRateRecord> {
        translateCritical(FakeOp.HeartRate)
        val total = totalInRange(hrCount, from, to)
        hrPagesServed = pagesFor(total)
        return stubList(total)
    }

    override suspend fun readRestingHeartRateSamples(
        from: Instant,
        to: Instant,
    ): List<RestingHeartRateRecord> {
        translateCritical(FakeOp.RestingHeartRate)
        return stubList(totalInRange(rhrCount, from, to))
    }

    override suspend fun readHrvSamples(
        from: Instant,
        to: Instant,
    ): List<HeartRateVariabilityRmssdRecord> {
        translateCritical(FakeOp.Hrv)
        return stubList(totalInRange(hrvCount, from, to))
    }

    override suspend fun readExerciseSessions(
        from: Instant,
        to: Instant,
    ): List<ExerciseSessionRecord> {
        translateCritical(FakeOp.Exercise)
        val total = totalInRange(exerciseCount, from, to)
        exercisePagesServed = pagesFor(total)
        return stubList(total)
    }

    override suspend fun readStepsRecords(
        from: Instant,
        to: Instant,
    ): List<StepsRecord> {
        translateCritical(FakeOp.Steps)
        val count = stepsByInstant.keys.count { inRange(it, from, to) }
        return stubList(count)
    }

    override suspend fun readSteps(
        from: Instant,
        to: Instant,
    ): Long {
        translateCritical(FakeOp.Steps)
        return stepsByInstant
            .filterKeys { inRange(it, from, to) }
            .values
            .sum()
    }

    override suspend fun readStepsRange(
        from: Instant,
        to: Instant,
    ): Map<LocalDate, Long> =
        try {
            errors[FakeOp.Steps]?.let { throw it }
            val zone = ZoneId.systemDefault()
            stepsByInstant
                .filterKeys { inRange(it, from, to) }
                .entries
                .groupBy { it.key.atZone(zone).toLocalDate() }
                .mapValues { (_, list) -> list.sumOf { it.value } }
        } catch (e: SecurityException) {
            throw HealthConnectPermissionRevokedException(e)
        } catch (e: HealthConnectPermissionRevokedException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }

    override suspend fun readWeightRecords(
        from: Instant,
        to: Instant,
    ): List<WeightRecord> =
        runOptional(FakeOp.Weight) {
            stubList(totalInRange(weightCount, from, to))
        }

    override suspend fun readBodyFatRecords(
        from: Instant,
        to: Instant,
    ): List<BodyFatRecord> =
        runOptional(FakeOp.BodyFat) {
            stubList(totalInRange(bodyFatCount, from, to))
        }

    override suspend fun readBloodPressureRecords(
        from: Instant,
        to: Instant,
    ): List<BloodPressureRecord> =
        runOptional(FakeOp.BloodPressure) {
            stubList(totalInRange(bpCount, from, to))
        }

    override suspend fun discoverDevices(windowDays: Int): List<String> {
        lastDiscoveryWindowDays = windowDays
        return try {
            errors[FakeOp.Discovery]?.let { throw it }
            devicesInWindow.toSortedSet().toList()
        } catch (e: SecurityException) {
            throw HealthConnectPermissionRevokedException(e)
        } catch (e: HealthConnectPermissionRevokedException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- helpers ----

    private fun translateCritical(op: FakeOp) {
        val e = errors[op] ?: return
        if (e is SecurityException) throw HealthConnectPermissionRevokedException(e)
        throw e
    }

    private inline fun <T> runOptional(
        op: FakeOp,
        block: () -> List<T>,
    ): List<T> =
        try {
            errors[op]?.let { throw it }
            block()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }

    private fun inRange(
        value: Instant,
        from: Instant,
        to: Instant,
    ): Boolean = !value.isBefore(from) && value.isBefore(to)

    private fun totalInRange(
        store: Map<Instant, Int>,
        from: Instant,
        to: Instant,
    ): Int =
        store
            .filterKeys { inRange(it, from, to) }
            .values
            .sum()

    private fun pagesFor(total: Int): Int =
        if (total == 0) 0 else (total + pageSize - 1) / pageSize

    /**
     * Build a typed list of [n] placeholder records. Tests only inspect [List.size];
     * never the field values, so reusing an `Any` sentinel cast through an
     * unchecked cast is safe for this suite.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> stubList(n: Int): List<T> = List(n) { SENTINEL as T }

    private companion object {
        private val SENTINEL: Any = Any()
    }
}
