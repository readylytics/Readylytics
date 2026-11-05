package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.repository.ExerciseSessionRead
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
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
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.PermissionStatus
import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.getOrNull
import app.readylytics.health.core.model.domain.util.logD
import app.readylytics.health.core.model.domain.util.logE
import app.readylytics.health.core.model.domain.util.logW
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Clock
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
        private val stepRecordReader: StepRecordReader,
        private val clock: Clock,
        private val availability: HealthConnectAvailability,
        private val permissionGateway: HealthConnectPermissionGateway,
        private val pagedReader: HealthConnectPagedReader,
        private val readGuard: HealthConnectReadGuard,
        private val exerciseReader: HealthConnectExerciseReader,
    ) : HealthConnectRepository {
        override val criticalPermissions: Set<String>
            get() = permissionGateway.criticalPermissions

        override fun isHistoryReadAvailable(): Boolean = availability.isHistoryReadAvailable()

        override fun isBackgroundReadAvailable(): Boolean = availability.isBackgroundReadAvailable()

        override val requiredPermissions: Set<String>
            get() = permissionGateway.requiredPermissions

        override val optionalPermissions: Set<String>
            get() = permissionGateway.optionalPermissions

        override val allPermissions: Set<String>
            get() = permissionGateway.allPermissions

        override val backgroundReadPermission: String
            get() = permissionGateway.backgroundReadPermission

        override fun isAvailable(): Boolean = availability.isAvailable()

        override suspend fun checkPermissions(): PermissionStatus = permissionGateway.checkPermissions()

        override suspend fun hasBodyTemperaturePermission(): Boolean =
            permissionGateway.hasReadPermission(BodyTemperatureRecord::class, "body temperature")

        override suspend fun hasStepsPermission(): Boolean =
            permissionGateway.hasReadPermission(StepsRecord::class, "steps")

        override suspend fun hasWeightPermission(): Boolean =
            permissionGateway.hasReadPermission(WeightRecord::class, "weight")

        override suspend fun hasDistancePermission(): Boolean =
            permissionGateway.hasReadPermission(DistanceRecord::class, "distance")

        override suspend fun hasBodyFatPermission(): Boolean =
            permissionGateway.hasReadPermission(BodyFatRecord::class, "body fat")

        override suspend fun hasBloodPressurePermission(): Boolean =
            permissionGateway.hasReadPermission(BloodPressureRecord::class, "blood pressure")

        override suspend fun hasOxygenSaturationPermission(): Boolean =
            permissionGateway.hasReadPermission(OxygenSaturationRecord::class, "oxygen saturation")

        override suspend fun hasVo2MaxPermission(): Boolean =
            permissionGateway.hasReadPermission(Vo2MaxRecord::class, "VO2 max")

        override suspend fun hasExerciseRoutesPermission(): Boolean =
            permissionGateway.hasExerciseRoutesPermission()

        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainSleepSessionRecord>> =
            readGuard.guardedRead(SleepSessionRecord::class, "Sleep session") {
                pagedReader.readAll<SleepSessionRecord>(from, to, retryScope).map { it.toDomain() }
            }

        override suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainHeartRateRecord>> =
            readGuard.guardedRead(HeartRateRecord::class, "Heart rate") {
                pagedReader.readAll<HeartRateRecord>(from, to, retryScope).map { it.toDomain() }
            }

        override suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainHrvRecord>> =
            readGuard.guardedRead(HeartRateVariabilityRmssdRecord::class, "HRV") {
                pagedReader.readAll<HeartRateVariabilityRmssdRecord>(from, to, retryScope).map { it.toDomain() }
            }

        override suspend fun readHeartRateSamplesPaged(
            from: Instant,
            to: Instant,
            startPageToken: String?,
            retryScope: ReadRetryScope?,
            onPage: suspend (List<DomainHeartRateRecord>, String?) -> Unit,
        ): ReadOutcome<Unit> =
            readGuard.guardedRead(HeartRateRecord::class, "Heart rate", logUnexpected = false) {
                pagedReader.readStreaming<HeartRateRecord>(from, to, startPageToken, retryScope) { page, nextToken ->
                    onPage(page.map { it.toDomain() }, nextToken)
                }
            }

        override suspend fun readHrvSamplesPaged(
            from: Instant,
            to: Instant,
            startPageToken: String?,
            retryScope: ReadRetryScope?,
            onPage: suspend (List<DomainHrvRecord>, String?) -> Unit,
        ): ReadOutcome<Unit> =
            readGuard.guardedRead(HeartRateVariabilityRmssdRecord::class, "HRV", logUnexpected = false) {
                pagedReader.readStreaming<HeartRateVariabilityRmssdRecord>(
                    from,
                    to,
                    startPageToken,
                    retryScope,
                ) { page, nextToken ->
                    onPage(page.map { it.toDomain() }, nextToken)
                }
            }

        override suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
            includeDetails: Boolean,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainExerciseSessionRecord>> =
            exerciseReader.readExerciseSessions(from, to, includeDetails, retryScope)

        override suspend fun readExerciseSessionsWithCompletion(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ExerciseSessionRead = exerciseReader.readExerciseSessionsWithCompletion(from, to, retryScope)

        override suspend fun readExerciseSession(id: String): DomainExerciseSessionRecord? =
            exerciseReader.readExerciseSession(id)

        override suspend fun readStepsRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainStepsRecord>> =
            stepRecordReader.readStepsRecords(from, to, retryScope)

        override suspend fun readStepsRecordsPaged(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
            onPage: suspend (records: List<DomainStepsRecord>) -> Unit,
        ): ReadOutcome<Unit> =
            stepRecordReader.readStepsRecordsPaged(from, to, retryScope, onPage)

        override suspend fun readSteps(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<Long> =
            stepRecordReader.readSteps(from, to, retryScope)

        override suspend fun readDailyStepTotals(
            from: Instant,
            to: Instant,
            zoneId: ZoneId,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<Map<LocalDate, Long>> =
            stepRecordReader.readDailyStepTotals(from, to, zoneId, retryScope)

        override suspend fun readWeightRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainWeightRecord>> =
            readGuard.guardedRead(WeightRecord::class, "Weight") {
                pagedReader.readAll<WeightRecord>(from, to, retryScope).map { it.toDomain() }
            }

        override suspend fun readBodyFatRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBodyFatRecord>> =
            readGuard.guardedRead(BodyFatRecord::class, "Body fat") {
                pagedReader.readAll<BodyFatRecord>(from, to, retryScope).map { it.toDomain() }
            }

        override suspend fun readBloodPressureRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBloodPressureRecord>> =
            readGuard.guardedRead(BloodPressureRecord::class, "Blood pressure") {
                pagedReader.readAll<BloodPressureRecord>(from, to, retryScope).map { it.toDomain() }
            }

        override suspend fun readOxygenSaturationRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainOxygenSaturationRecord>> =
            readGuard.guardedRead(OxygenSaturationRecord::class, "Oxygen saturation") {
                pagedReader.readAll<OxygenSaturationRecord>(from, to, retryScope).map { it.toDomain() }
            }

        override suspend fun readBodyTemperatureRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBodyTemperatureRecord>> =
            readGuard.guardedRead(BodyTemperatureRecord::class, "Body temperature") {
                pagedReader.readAll<BodyTemperatureRecord>(from, to, retryScope).map { it.toDomain() }
            }

        override suspend fun readVo2MaxRecords(
            startTime: Instant,
            endTime: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainVo2MaxRecord>> =
            readGuard.guardedRead(Vo2MaxRecord::class, "VO2 max") {
                pagedReader.readAll<Vo2MaxRecord>(startTime, endTime, retryScope).map { it.toDomain() }
            }

        private suspend fun <T> readOrEmpty(block: suspend () -> ReadOutcome<List<T>>): List<T> =
            try {
                block().getOrNull() ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("HealthConnectRepository", e) {
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
                logW("HealthConnectRepository", e) {
                    "$label device scan failed; returning partial results"
                }
            }
            return localDevices
        }

        override suspend fun discoverDevices(windowDays: Int): List<String> =
            readGuard.runOnIo {
                try {
                    logD(
                        "HealthConnectRepository",
                    ) { "Discovering devices in $windowDays day window..." }
                    val from = clock.instant().minusSeconds(windowDays.toLong() * TimeUnit.DAYS.toSeconds(1))
                    val to = clock.instant()

                    val devices = mutableSetOf<String>()

                    coroutineScope {
                        // Each read is wrapped so a single revoked/missing permission can't
                        // cancel the whole scope and collapse discovery to an empty list.
                        val sleepSessionsDeferred =
                            async { readOrEmpty { readSleepSessions(from, to) } }
                        val hrDevicesDeferred =
                            async {
                                collectDeviceNames("Heart rate") { onNames ->
                                    readHeartRateSamplesPaged(from, to) { page, _ ->
                                        onNames(page.map { it.deviceName })
                                    }
                                }
                            }
                        val hrvDevicesDeferred =
                            async {
                                collectDeviceNames("HRV") { onNames ->
                                    readHrvSamplesPaged(from, to) { page, _ ->
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

                    logD(
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
                    logE(
                        "HealthConnectRepository",
                        e,
                    ) { "Device discovery failed" }
                    emptyList()
                }
            }
    }



