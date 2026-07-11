package app.readylytics.health.data.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import app.readylytics.health.domain.model.DomainBloodPressureRecord
import app.readylytics.health.domain.model.DomainBodyFatRecord
import app.readylytics.health.domain.model.DomainExerciseRoute
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainRoutePoint
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHeartRateSample
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainSleepStage
import app.readylytics.health.domain.model.DomainSleepStageType
import app.readylytics.health.domain.model.DomainStepsRecord
import app.readylytics.health.domain.model.DomainWeightRecord
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.PermissionStatus
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
    Hrv,
    Exercise,
    Steps,
    Weight,
    BodyFat,
    BloodPressure,
    OxygenSaturation,
    Discovery,
    ExerciseRoute,
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
    val exerciseRoutes = mutableMapOf<String, DomainExerciseRoute>()

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
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        )

    override val allPermissions: Set<String> = requiredPermissions + optionalPermissions

    override val backgroundReadPermission: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    // ---- state knobs ----

    var sdkAvailable: Boolean = true
    var granted: Set<String> = emptySet()
    var permissionError: Throwable? = null
    val errors: MutableMap<FakeOp, Throwable> = mutableMapOf()

    val sleepCount: MutableMap<Instant, Int> = mutableMapOf()
    val hrCount: MutableMap<Instant, Int> = mutableMapOf()
    val hrvCount: MutableMap<Instant, Int> = mutableMapOf()
    val exerciseCount: MutableMap<Instant, Int> = mutableMapOf()
    val weightCount: MutableMap<Instant, Int> = mutableMapOf()
    val bodyFatCount: MutableMap<Instant, Int> = mutableMapOf()
    val bpCount: MutableMap<Instant, Int> = mutableMapOf()
    val spo2Count: MutableMap<Instant, Int> = mutableMapOf()

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
    ): List<DomainSleepSessionRecord> {
        translateCritical(FakeOp.Sleep)
        val total = totalInRange(sleepCount, from, to)
        sleepPagesServed = pagesFor(total)
        return stubList(total) { index -> placeholderSleep(index) }
    }

    override suspend fun readHeartRateSamples(
        from: Instant,
        to: Instant,
    ): List<DomainHeartRateRecord> {
        translateCritical(FakeOp.HeartRate)
        val total = totalInRange(hrCount, from, to)
        hrPagesServed = pagesFor(total)
        return stubList(total) { index -> placeholderHeartRate(index) }
    }

    override suspend fun readHrvSamples(
        from: Instant,
        to: Instant,
    ): List<DomainHrvRecord> {
        translateCritical(FakeOp.Hrv)
        return stubList(totalInRange(hrvCount, from, to)) { index -> placeholderHrv(index) }
    }

    override suspend fun readExerciseSessions(
        from: Instant,
        to: Instant,
    ): List<DomainExerciseSessionRecord> {
        translateCritical(FakeOp.Exercise)
        val total = totalInRange(exerciseCount, from, to)
        exercisePagesServed = pagesFor(total)
        return stubList(total) { index -> placeholderExercise(index) }
    }

    override suspend fun readStepsRecords(
        from: Instant,
        to: Instant,
    ): List<DomainStepsRecord> {
        translateCritical(FakeOp.Steps)
        val count = stepsByInstant.keys.count { inRange(it, from, to) }
        return stubList(count) { index -> placeholderSteps(index) }
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
    ): List<DomainWeightRecord> =
        runOptional(FakeOp.Weight) {
            stubList(totalInRange(weightCount, from, to)) { index -> placeholderWeight(index) }
        }

    override suspend fun readBodyFatRecords(
        from: Instant,
        to: Instant,
    ): List<DomainBodyFatRecord> =
        runOptional(FakeOp.BodyFat) {
            stubList(totalInRange(bodyFatCount, from, to)) { index -> placeholderBodyFat(index) }
        }

    override suspend fun readBloodPressureRecords(
        from: Instant,
        to: Instant,
    ): List<DomainBloodPressureRecord> =
        runOptional(FakeOp.BloodPressure) {
            stubList(totalInRange(bpCount, from, to)) { index -> placeholderBloodPressure(index) }
        }

    override suspend fun readOxygenSaturationRecords(
        from: Instant,
        to: Instant,
    ): List<DomainOxygenSaturationRecord> =
        runOptional(FakeOp.OxygenSaturation) {
            stubList(totalInRange(spo2Count, from, to)) { index -> placeholderOxygen(index) }
        }

    override suspend fun readExerciseRoute(sessionId: String): DomainExerciseRoute? {
        errors[FakeOp.ExerciseRoute]?.let { throw it }
        return exerciseRoutes[sessionId]
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

    private fun pagesFor(total: Int): Int = if (total == 0) 0 else (total + pageSize - 1) / pageSize

    private fun <T> stubList(
        n: Int,
        factory: (Int) -> T,
    ): List<T> = List(n, factory)

    private fun placeholderSleep(index: Int): DomainSleepSessionRecord =
        DomainSleepSessionRecord(
            id = "sleep-$index",
            startTime = PLACEHOLDER_TIME,
            endTime = PLACEHOLDER_TIME.plusSeconds(3600),
            startZoneOffsetSeconds = 0,
            endZoneOffsetSeconds = 0,
            deviceName = PLACEHOLDER_DEVICE,
            stages =
                listOf(
                    DomainSleepStage(
                        startTime = PLACEHOLDER_TIME,
                        endTime = PLACEHOLDER_TIME.plusSeconds(3600),
                        stageType = DomainSleepStageType.UNKNOWN,
                    ),
                ),
        )

    private fun placeholderHeartRate(index: Int): DomainHeartRateRecord =
        DomainHeartRateRecord(
            id = "hr-$index",
            deviceName = PLACEHOLDER_DEVICE,
            samples = listOf(DomainHeartRateSample(time = PLACEHOLDER_TIME, beatsPerMinute = 60)),
        )

    private fun placeholderHrv(index: Int): DomainHrvRecord =
        DomainHrvRecord(
            id = "hrv-$index",
            time = PLACEHOLDER_TIME,
            rmssdMs = 42f,
            deviceName = PLACEHOLDER_DEVICE,
        )

    private fun placeholderExercise(index: Int): DomainExerciseSessionRecord =
        DomainExerciseSessionRecord(
            id = "exercise-$index",
            startTime = PLACEHOLDER_TIME,
            endTime = PLACEHOLDER_TIME.plusSeconds(1800),
            exerciseType = "running",
            deviceName = PLACEHOLDER_DEVICE,
        )

    private fun placeholderSteps(index: Int): DomainStepsRecord =
        DomainStepsRecord(
            startTime = PLACEHOLDER_TIME.plusSeconds(index.toLong()),
            count = 1L,
            deviceName = PLACEHOLDER_DEVICE,
        )

    private fun placeholderWeight(index: Int): DomainWeightRecord =
        DomainWeightRecord(
            id = "weight-$index",
            time = PLACEHOLDER_TIME,
            weightKg = 70f,
            deviceName = PLACEHOLDER_DEVICE,
        )

    private fun placeholderBodyFat(index: Int): DomainBodyFatRecord =
        DomainBodyFatRecord(
            id = "body-fat-$index",
            time = PLACEHOLDER_TIME,
            percentage = 0.2f,
            deviceName = PLACEHOLDER_DEVICE,
        )

    private fun placeholderBloodPressure(index: Int): DomainBloodPressureRecord =
        DomainBloodPressureRecord(
            id = "bp-$index",
            time = PLACEHOLDER_TIME,
            systolicMmHg = 120,
            diastolicMmHg = 80,
            deviceName = PLACEHOLDER_DEVICE,
        )

    private fun placeholderOxygen(index: Int): DomainOxygenSaturationRecord =
        DomainOxygenSaturationRecord(
            id = "spo2-$index",
            time = PLACEHOLDER_TIME,
            percentage = 0.98f,
            deviceName = PLACEHOLDER_DEVICE,
        )

    private companion object {
        private const val PLACEHOLDER_DEVICE = "fake-device"
        private val PLACEHOLDER_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
