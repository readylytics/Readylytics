package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record as HcRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.readylytics.health.core.model.di.IoDispatcher
import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.PermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectRepositoryImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
        private val stepRecordReader: StepRecordReader,
        private val intervalTotalsReader: IntervalTotalsReader,
    ) : HealthConnectRepository {
        override val criticalPermissions: Set<String> =
            setOf(
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            )

        override val requiredPermissions: Set<String> =
            criticalPermissions +
                setOf("android.permission.health.READ_HEALTH_DATA_HISTORY")

        override val optionalPermissions: Set<String> =
            setOf(
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getReadPermission(BodyFatRecord::class),
                HealthPermission.getReadPermission(BloodPressureRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class),
                HealthPermission.getReadPermission(BodyTemperatureRecord::class),
                // The recording app writes a workout's distance and elevation gain as separate
                // records, not on the ExerciseSessionRecord. Without these the app can only
                // integrate the GPS polyline, which reads ~1-3% short of the source app.
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(ElevationGainedRecord::class),
                // READ_EXERCISE_ROUTES is deliberately absent. Health Connect does not expose routes
                // in the bulk data-type permission sheet -- it lives under "Additional access"
                // (alongside background and past-data access) as a tri-state Always allow / Ask every
                // time / Don't allow, defaulting to "Ask every time". Requesting it here is silently
                // dropped: on a clean install every other permission comes back USER_SET while routes
                // comes back with no user decision at all. Routes are obtained per workout through
                // ExerciseRouteRequestContract instead (see ui/health/ExerciseRoutePermissionRequest).
                // It must stay declared in AndroidManifest.xml -- that declaration is what makes the
                // "Access exercise routes" row appear in Health Connect settings.
            )

        override val allPermissions: Set<String> =
            requiredPermissions + optionalPermissions

        override val backgroundReadPermission: String =
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

        private val client: HealthConnectClient by lazy {
            HealthConnectClient.getOrCreate(context)
        }

        override fun isAvailable(): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        override suspend fun checkPermissions(): PermissionStatus =
            withContext(ioDispatcher) {
                app.readylytics.health.core.model.domain.util.logD(
                    "HealthConnectRepository",
                ) { "Checking permissions..." }
                if (!isAvailable()) {
                    app.readylytics.health.core.model.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "SDK not available" }
                    return@withContext PermissionStatus.Unavailable
                }
                val granted =
                    try {
                        client.permissionController.getGrantedPermissions()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        app.readylytics.health.core.model.domain.util.logE("HealthConnectRepository", e) {
                            "Failed to get granted permissions"
                        }
                        throw e
                    }
                if (app.readylytics.health.core.healthconnect.BuildConfig.DEBUG) {
                    app.readylytics.health.core.model.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "Granted permissions: $granted" }
                    app.readylytics.health.core.model.domain.util.logD("HealthConnectRepository") {
                        "Required permissions: $requiredPermissions"
                    }
                } else {
                    app.readylytics.health.core.model.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "Granted permissions count: ${granted.size}" }
                }

                if (granted.containsAll(requiredPermissions)) {
                    app.readylytics.health.core.model.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "All required permissions granted" }
                    PermissionStatus.Granted
                } else {
                    val missing = requiredPermissions - granted
                    if (app.readylytics.health.core.healthconnect.BuildConfig.DEBUG) {
                        app.readylytics.health.core.model.domain.util.logI(
                            "HealthConnectRepository",
                        ) { "Missing permissions: $missing" }
                    } else {
                        app.readylytics.health.core.model.domain.util.logD(
                            "HealthConnectRepository",
                        ) { "Missing permissions count: ${missing.size}" }
                    }
                    PermissionStatus.Missing(missing)
                }
            }

        override suspend fun hasBodyTemperaturePermission(): Boolean =
            hasPermission<BodyTemperatureRecord>("body temperature")

        override suspend fun hasStepsPermission(): Boolean =
            hasPermission<StepsRecord>("steps")

        override suspend fun hasWeightPermission(): Boolean =
            hasPermission<WeightRecord>("weight")

        override suspend fun hasDistancePermission(): Boolean =
            hasPermission<DistanceRecord>("distance")

        override suspend fun hasBodyFatPermission(): Boolean =
            hasPermission<BodyFatRecord>("body fat")

        override suspend fun hasBloodPressurePermission(): Boolean =
            hasPermission<BloodPressureRecord>("blood pressure")

        override suspend fun hasOxygenSaturationPermission(): Boolean =
            hasPermission<OxygenSaturationRecord>("oxygen saturation")

        override suspend fun hasExerciseRoutesPermission(): Boolean =
            withContext(ioDispatcher) {
                if (!isAvailable()) return@withContext false
                try {
                    val granted = client.permissionController.getGrantedPermissions()
                    granted.contains("android.permission.health.READ_EXERCISE_ROUTES") ||
                        granted.contains("com.google.android.apps.healthdata.permission.READ_EXERCISE_ROUTES")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.core.model.domain.util.logE("HealthConnectRepository", e) {
                        "Failed to check exercise routes permission"
                    }
                    false
                }
            }

        private suspend inline fun <reified T : androidx.health.connect.client.records.Record> hasPermission(
            label: String,
        ): Boolean =
            withContext(ioDispatcher) {
                if (!isAvailable()) return@withContext false
                try {
                    client.permissionController
                        .getGrantedPermissions()
                        .contains(HealthPermission.getReadPermission(T::class))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.core.model.domain.util.logE("HealthConnectRepository", e) {
                        "Failed to check $label permission"
                    }
                    false
                }
            }

        private suspend inline fun <reified T : androidx.health.connect.client.records.Record> readAllPages(
            from: Instant,
            to: Instant,
        ): List<T> {
            val all = mutableListOf<T>()
            readAllPagesStreaming<T>(from, to) { page -> all.addAll(page) }
            return all
        }

        /**
         * Paged variant of [readAllPages]: invokes [onPage] once per Health Connect page instead of
         * accumulating every page into one list, so a dense window never holds more than one page
         * of raw HC records in memory at once (HC-001).
         */
        private suspend inline fun <reified T : androidx.health.connect.client.records.Record> readAllPagesStreaming(
            from: Instant,
            to: Instant,
            onPage: suspend (List<T>) -> Unit,
        ) {
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
                    onPage(response.records)
                    pageToken = response.pageToken
                } while (pageToken != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rethrowReadFailureOrOriginal(T::class.simpleName, e)
            }
        }

        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
        ): List<DomainSleepSessionRecord> =
            withContext(ioDispatcher) {
                readAllPages<SleepSessionRecord>(from, to).map { it.toDomain() }
            }

        override suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
        ): List<DomainHeartRateRecord> =
            withContext(ioDispatcher) {
                readAllPages<HeartRateRecord>(from, to).map { it.toDomain() }
            }

        override suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
        ): List<DomainHrvRecord> =
            withContext(ioDispatcher) {
                readAllPages<HeartRateVariabilityRmssdRecord>(from, to).map { it.toDomain() }
            }

        override suspend fun readHeartRateSamplesPaged(
            from: Instant,
            to: Instant,
            onPage: suspend (List<DomainHeartRateRecord>) -> Unit,
        ) {
            withContext(ioDispatcher) {
                readAllPagesStreaming<HeartRateRecord>(from, to) { page ->
                    onPage(page.map { it.toDomain() })
                }
            }
        }

        override suspend fun readHrvSamplesPaged(
            from: Instant,
            to: Instant,
            onPage: suspend (List<DomainHrvRecord>) -> Unit,
        ) {
            withContext(ioDispatcher) {
                readAllPagesStreaming<HeartRateVariabilityRmssdRecord>(from, to) { page ->
                    onPage(page.map { it.toDomain() })
                }
            }
        }

        override suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
            includeDetails: Boolean,
        ): List<DomainExerciseSessionRecord> =
            withContext(ioDispatcher) {
                val sessions = readAllPages<ExerciseSessionRecord>(from, to)
                if (sessions.isEmpty() || !includeDetails) {
                    return@withContext sessions.map { it.toDomain(null) }
                }
                // Two bulk reads for the whole window, not one per session: DistanceRecord and
                // ElevationGainedRecord are low-volume, and attribution happens in memory.
                val distanceTotals = intervalTotalsReader.readDistanceTotals(from, to)
                val elevationTotals = intervalTotalsReader.readElevationTotals(from, to)

                sessions.map { session ->
                    // Routes are only returned by a per-record read, so this is an extra IPC
                    // round-trip per session.
                    val routeResult =
                        try {
                            val record =
                                client
                                    .readRecord(ExerciseSessionRecord::class, session.metadata.id)
                                    .record
                            val result = record.exerciseRouteResult
                            app.readylytics.health.core.model.domain.util.logD("HealthConnectRepository") {
                                "Exercise session ${session.metadata.id} (${session.exerciseType}) " +
                                    "route result: ${result.javaClass.simpleName}"
                            }
                            result
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: SecurityException) {
                            app.readylytics.health.core.model.domain.util.logW("HealthConnectRepository") {
                                "SecurityException reading route for session ${session.metadata.id}: ${e.message}"
                            }
                            ExerciseRouteResult.ConsentRequired()
                        } catch (e: Exception) {
                            app.readylytics.health.core.model.domain.util.logE("HealthConnectRepository", e) {
                                "Failed to read route for session ${session.metadata.id}"
                            }
                            ExerciseRouteResult.NoData()
                        }
                    session.toDomain(
                        routeResult = routeResult,
                        totalDistanceMeters = intervalTotalsReader.resolveTotal(session, distanceTotals),
                        elevationGainMeters = intervalTotalsReader.resolveTotal(session, elevationTotals),
                    )
                }
            }

        override suspend fun readExerciseSession(id: String): DomainExerciseSessionRecord? =
            withContext(ioDispatcher) {
                try {
                    val record = client.readRecord(ExerciseSessionRecord::class, id).record
                    val routeResult = record.exerciseRouteResult
                    app.readylytics.health.core.model.domain.util.logD("HealthConnectRepository") {
                        "Read single exercise session $id (${record.exerciseType}) " +
                            "route result: ${routeResult.javaClass.simpleName}"
                    }
                    val distanceTotals =
                        intervalTotalsReader.readDistanceTotals(record.startTime, record.endTime)
                    val elevationTotals =
                        intervalTotalsReader.readElevationTotals(record.startTime, record.endTime)
                    record.toDomain(
                        routeResult = routeResult,
                        totalDistanceMeters = intervalTotalsReader.resolveTotal(record, distanceTotals),
                        elevationGainMeters = intervalTotalsReader.resolveTotal(record, elevationTotals),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    app.readylytics.health.core.model.domain.util.logW("HealthConnectRepository") {
                        "SecurityException reading exercise session $id: ${e.message}"
                    }
                    null
                } catch (e: Exception) {
                    app.readylytics.health.core.model.domain.util.logE("HealthConnectRepository", e) {
                        "Failed to read exercise session $id"
                    }
                    null
                }
            }

        override suspend fun readStepsRecords(
            from: Instant,
            to: Instant,
        ): List<DomainStepsRecord> =
            stepRecordReader.readStepsRecords(from, to)

        override suspend fun readSteps(
            from: Instant,
            to: Instant,
        ): Long =
            stepRecordReader.readSteps(from, to)

        override suspend fun readDailyStepTotals(
            from: Instant,
            to: Instant,
            zoneId: ZoneId,
        ): Map<LocalDate, Long> =
            stepRecordReader.readDailyStepTotals(from, to, zoneId)

        override suspend fun readWeightRecords(
            from: Instant,
            to: Instant,
        ): List<DomainWeightRecord> =
            readOptionalRecords<WeightRecord, _>("Weight", from, to) { it.toDomain() }

        override suspend fun readBodyFatRecords(
            from: Instant,
            to: Instant,
        ): List<DomainBodyFatRecord> =
            readOptionalRecords<BodyFatRecord, _>("Body fat", from, to) { it.toDomain() }

        override suspend fun readBloodPressureRecords(
            from: Instant,
            to: Instant,
        ): List<DomainBloodPressureRecord> =
            readOptionalRecords<BloodPressureRecord, _>("Blood pressure", from, to) { it.toDomain() }

        override suspend fun readOxygenSaturationRecords(
            from: Instant,
            to: Instant,
        ): List<DomainOxygenSaturationRecord> =
            readOptionalRecords<OxygenSaturationRecord, _>("Oxygen saturation", from, to) { it.toDomain() }

        override suspend fun readBodyTemperatureRecords(
            from: Instant,
            to: Instant,
        ): List<DomainBodyTemperatureRecord> =
            readOptionalRecords<BodyTemperatureRecord, _>("Body temperature", from, to) { it.toDomain() }

        private suspend inline fun <reified T : androidx.health.connect.client.records.Record, R> readOptionalRecords(
            label: String,
            from: Instant,
            to: Instant,
            crossinline transform: (T) -> R,
        ): List<R> =
            withContext(ioDispatcher) {
                try {
                    readAllPages<T>(from, to).map { transform(it) }
                } catch (e: HealthConnectPermissionRevokedException) {
                    app.readylytics.health.core.model.domain.util.logD("HealthConnectRepository") {
                        "$label record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    app.readylytics.health.core.model.domain.util.logD("HealthConnectRepository") {
                        "$label record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Transient IO/rate-limit errors must propagate so retryWithBackoff can act on
                    // them, rather than being indistinguishable from "user has no data" (HC-008).
                    app.readylytics.health.core.model.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading $label records"
                    }
                    throw e
                }
            }

        private suspend fun <T> readOrEmpty(block: suspend () -> List<T>): List<T> =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.readylytics.health.core.model.domain.util.logW("HealthConnectRepository", e) {
                    "Read failed; returning empty list"
                }
                emptyList()
            }

        /**
         * HC-001: collects device names from a streaming paged read without ever materializing the
         * full [from]..[to] record list. [readPaged] is expected to be a partial application of
         * readHeartRateSamplesPaged/readHrvSamplesPaged that forwards each page's device names to
         * its callback.
         */
        private suspend fun collectDeviceNames(
            label: String,
            readPaged: suspend (onNames: suspend (List<String>) -> Unit) -> Unit,
        ): Set<String> {
            val localDevices = mutableSetOf<String>()
            try {
                readPaged { names -> localDevices.addAll(names) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.readylytics.health.core.model.domain.util.logW("HealthConnectRepository", e) {
                    "$label device scan failed; returning partial results"
                }
            }
            return localDevices
        }

        override suspend fun discoverDevices(windowDays: Int): List<String> =
            withContext(ioDispatcher) {
                try {
                    app.readylytics.health.core.model.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "Discovering devices in $windowDays day window..." }
                    val from = Instant.now().minusSeconds(windowDays.toLong() * TimeUnit.DAYS.toSeconds(1))
                    val to = Instant.now()

                    val devices = mutableSetOf<String>()

                    coroutineScope {
                        // Each read is wrapped so a single revoked/missing permission can't
                        // cancel the whole scope and collapse discovery to an empty list.
                        val sleepSessionsDeferred =
                            async { readOrEmpty { readSleepSessions(from, to) } }
                        val hrDevicesDeferred =
                            async {
                                collectDeviceNames("Heart rate") { onNames ->
                                    readHeartRateSamplesPaged(from, to) { page ->
                                        onNames(page.map { it.deviceName })
                                    }
                                }
                            }
                        val hrvDevicesDeferred =
                            async {
                                collectDeviceNames("HRV") { onNames ->
                                    readHrvSamplesPaged(from, to) { page ->
                                        onNames(page.map { it.deviceName })
                                    }
                                }
                            }
                        val workoutRecordsDeferred =
                            // Discovery only reads deviceName -- reading routes here would add one
                            // IPC round-trip per workout and block the source picker for nothing.
                            async { readOrEmpty { readExerciseSessions(from, to, includeDetails = false) } }
                        val stepsRecordsDeferred =
                            async { readOrEmpty { readStepsRecords(from, to) } }
                        val weightRecordsDeferred =
                            async { readOrEmpty { readWeightRecords(from, to) } }
                        val bodyFatRecordsDeferred =
                            async { readOrEmpty { readBodyFatRecords(from, to) } }
                        val bloodPressureRecordsDeferred =
                            async { readOrEmpty { readBloodPressureRecords(from, to) } }
                        val spo2RecordsDeferred =
                            async { readOrEmpty { readOxygenSaturationRecords(from, to) } }
                        val bodyTemperatureRecordsDeferred =
                            async { readOrEmpty { readBodyTemperatureRecords(from, to) } }

                        sleepSessionsDeferred.await().forEach { devices.add(it.deviceName) }
                        devices.addAll(hrDevicesDeferred.await())
                        devices.addAll(hrvDevicesDeferred.await())
                        workoutRecordsDeferred.await().forEach { devices.add(it.deviceName) }

                        // Steps are frequently the only data the phone records, so scanning
                        // them here is what surfaces the phone as a selectable source device.
                        stepsRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                        weightRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                        bodyFatRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                        bloodPressureRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                        spo2RecordsDeferred.await().forEach { devices.add(it.deviceName) }
                        bodyTemperatureRecordsDeferred.await().forEach { devices.add(it.deviceName) }
                    }

                    app.readylytics.health.core.model.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "Device discovery found ${devices.size} unique devices" }
                    devices.sorted()
                } catch (e: SecurityException) {
                    throw HealthConnectPermissionRevokedException(
                        cause = e,
                        operation = "discoverDevices",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.core.model.domain.util.logE(
                        "HealthConnectRepository",
                        e,
                    ) { "Device discovery failed" }
                    emptyList()
                }
            }
    }
