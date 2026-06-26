package app.readylytics.health.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.readylytics.health.di.IoDispatcher
import app.readylytics.health.domain.model.DomainBloodPressureRecord
import app.readylytics.health.domain.model.DomainBodyFatRecord
import app.readylytics.health.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.domain.model.DomainHeartRateRecord
import app.readylytics.health.domain.model.DomainHeartRateSample
import app.readylytics.health.domain.model.DomainHrvRecord
import app.readylytics.health.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.domain.model.DomainRestingHeartRateRecord
import app.readylytics.health.domain.model.DomainSleepSessionRecord
import app.readylytics.health.domain.model.DomainSleepStage
import app.readylytics.health.domain.model.DomainSleepStageType
import app.readylytics.health.domain.model.DomainStepsRecord
import app.readylytics.health.domain.model.DomainWeightRecord
import app.readylytics.health.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.PermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectRepositoryImpl
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : HealthConnectRepository {
        override val criticalPermissions: Set<String> =
            setOf(
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class),
            )

        override val requiredPermissions: Set<String> =
            criticalPermissions +
                setOf("android.permission.health.READ_HEALTH_DATA_HISTORY")

        override val optionalPermissions: Set<String> =
            setOf(
                HealthPermission.getReadPermission(WeightRecord::class),
                HealthPermission.getReadPermission(BodyFatRecord::class),
                HealthPermission.getReadPermission(BloodPressureRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class),
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
                app.readylytics.health.domain.util.logD(
                    "HealthConnectRepository",
                ) { "Checking permissions..." }
                if (!isAvailable()) {
                    app.readylytics.health.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "SDK not available" }
                    return@withContext PermissionStatus.Unavailable
                }
                val granted =
                    try {
                        client.permissionController.getGrantedPermissions()
                    } catch (e: Exception) {
                        app.readylytics.health.domain.util.logE("HealthConnectRepository", e) {
                            "Failed to get granted permissions"
                        }
                        throw e
                    }
                app.readylytics.health.domain.util.logD(
                    "HealthConnectRepository",
                ) { "Granted permissions: $granted" }
                app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                    "Required permissions: $requiredPermissions"
                }

                if (granted.containsAll(requiredPermissions)) {
                    app.readylytics.health.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "All required permissions granted" }
                    PermissionStatus.Granted
                } else {
                    val missing = requiredPermissions - granted
                    app.readylytics.health.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "Missing permissions: $missing" }
                    PermissionStatus.Missing(missing)
                }
            }

        private suspend inline fun <reified T : androidx.health.connect.client.records.Record> readAllPages(
            from: Instant,
            to: Instant,
        ): List<T> {
            val all = mutableListOf<T>()
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
                    all.addAll(response.records)
                    pageToken = response.pageToken
                } while (pageToken != null)
            } catch (e: SecurityException) {
                throw HealthConnectPermissionRevokedException(e)
            }
            return all
        }

        private fun SleepSessionRecord.toDomain(): DomainSleepSessionRecord =
            DomainSleepSessionRecord(
                id = metadata.id,
                startTime = startTime,
                endTime = endTime,
                startZoneOffsetSeconds = startZoneOffset?.totalSeconds,
                endZoneOffsetSeconds = endZoneOffset?.totalSeconds,
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
                stages =
                    stages.map { stage ->
                        DomainSleepStage(
                            startTime = stage.startTime,
                            endTime = stage.endTime,
                            stageType =
                                when (stage.stage) {
                                    SleepSessionRecord.STAGE_TYPE_DEEP -> DomainSleepStageType.DEEP
                                    SleepSessionRecord.STAGE_TYPE_REM -> DomainSleepStageType.REM
                                    SleepSessionRecord.STAGE_TYPE_LIGHT,
                                    SleepSessionRecord.STAGE_TYPE_SLEEPING,
                                    -> DomainSleepStageType.LIGHT
                                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                                    -> DomainSleepStageType.AWAKE
                                    else -> DomainSleepStageType.UNKNOWN
                                },
                        )
                    },
            )

        private fun HeartRateRecord.toDomain(): DomainHeartRateRecord =
            DomainHeartRateRecord(
                id = metadata.id,
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
                samples =
                    samples.map { sample ->
                        DomainHeartRateSample(
                            time = sample.time,
                            beatsPerMinute = sample.beatsPerMinute.toInt(),
                        )
                    },
            )

        private fun RestingHeartRateRecord.toDomain(): DomainRestingHeartRateRecord =
            DomainRestingHeartRateRecord(
                id = metadata.id,
                time = time,
                beatsPerMinute = beatsPerMinute.toInt(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun HeartRateVariabilityRmssdRecord.toDomain(): DomainHrvRecord =
            DomainHrvRecord(
                id = metadata.id,
                time = time,
                rmssdMs = heartRateVariabilityMillis.toFloat(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun ExerciseSessionRecord.toDomain(): DomainExerciseSessionRecord =
            DomainExerciseSessionRecord(
                id = metadata.id,
                startTime = startTime,
                endTime = endTime,
                exerciseType = exerciseType.toString(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun StepsRecord.toDomain(): DomainStepsRecord =
            DomainStepsRecord(
                startTime = startTime,
                count = count,
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun WeightRecord.toDomain(): DomainWeightRecord =
            DomainWeightRecord(
                id = metadata.id,
                time = time,
                weightKg = weight.inKilograms.toFloat(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun BodyFatRecord.toDomain(): DomainBodyFatRecord =
            DomainBodyFatRecord(
                id = metadata.id,
                time = time,
                percentage = percentage.value.toFloat(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun BloodPressureRecord.toDomain(): DomainBloodPressureRecord =
            DomainBloodPressureRecord(
                id = metadata.id,
                time = time,
                systolicMmHg = systolic.inMillimetersOfMercury.toInt(),
                diastolicMmHg = diastolic.inMillimetersOfMercury.toInt(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

        private fun OxygenSaturationRecord.toDomain(): DomainOxygenSaturationRecord =
            DomainOxygenSaturationRecord(
                id = metadata.id,
                time = time,
                percentage = percentage.value.toFloat(),
                deviceName = DeviceLabel.from(metadata.device, metadata.dataOrigin),
            )

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

        override suspend fun readRestingHeartRateSamples(
            from: Instant,
            to: Instant,
        ): List<DomainRestingHeartRateRecord> =
            withContext(ioDispatcher) {
                readAllPages<RestingHeartRateRecord>(from, to).map { it.toDomain() }
            }

        override suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
        ): List<DomainHrvRecord> =
            withContext(ioDispatcher) {
                readAllPages<HeartRateVariabilityRmssdRecord>(from, to).map { it.toDomain() }
            }

        override suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
        ): List<DomainExerciseSessionRecord> =
            withContext(ioDispatcher) {
                readAllPages<ExerciseSessionRecord>(from, to).map { it.toDomain() }
            }

        override suspend fun readStepsRecords(
            from: Instant,
            to: Instant,
        ): List<DomainStepsRecord> =
            withContext(ioDispatcher) {
                readAllPages<StepsRecord>(from, to).map { it.toDomain() }
            }

        override suspend fun readSteps(
            from: Instant,
            to: Instant,
        ): Long =
            withContext(ioDispatcher) {
                val result =
                    client.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(from, to),
                        ),
                    )
                result[StepsRecord.COUNT_TOTAL] ?: 0L
            }

        override suspend fun readStepsRange(
            from: Instant,
            to: Instant,
        ): Map<java.time.LocalDate, Long> =
            withContext(ioDispatcher) {
                try {
                    val records = readAllPages<StepsRecord>(from, to)
                    val zoneId = java.time.ZoneId.systemDefault()
                    records
                        .groupBy { record ->
                            record.startTime.atZone(zoneId).toLocalDate()
                        }.mapValues { (_, dayRecords) ->
                            dayRecords.sumOf { it.count }
                        }
                } catch (e: SecurityException) {
                    throw HealthConnectPermissionRevokedException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.domain.util.logE("HealthConnectRepository", e) {
                        "Error batch fetching steps"
                    }
                    emptyMap()
                }
            }

        override suspend fun readWeightRecords(
            from: Instant,
            to: Instant,
        ): List<DomainWeightRecord> =
            withContext(ioDispatcher) {
                try {
                    readAllPages<WeightRecord>(from, to).map { it.toDomain() }
                } catch (e: HealthConnectPermissionRevokedException) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                        "Weight record permission not granted"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                        "Weight record permission not granted"
                    }
                    emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading weight records"
                    }
                    emptyList()
                }
            }

        override suspend fun readBodyFatRecords(
            from: Instant,
            to: Instant,
        ): List<DomainBodyFatRecord> =
            withContext(ioDispatcher) {
                try {
                    readAllPages<BodyFatRecord>(from, to).map { it.toDomain() }
                } catch (e: HealthConnectPermissionRevokedException) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                        "Body fat record permission not granted"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                        "Body fat record permission not granted"
                    }
                    emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading body fat records"
                    }
                    emptyList()
                }
            }

        override suspend fun readBloodPressureRecords(
            from: Instant,
            to: Instant,
        ): List<DomainBloodPressureRecord> =
            withContext(ioDispatcher) {
                try {
                    readAllPages<BloodPressureRecord>(from, to).map { it.toDomain() }
                } catch (e: HealthConnectPermissionRevokedException) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                        "Blood pressure record permission not granted"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                        "Blood pressure record permission not granted"
                    }
                    emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading blood pressure records"
                    }
                    emptyList()
                }
            }

        override suspend fun readOxygenSaturationRecords(
            from: Instant,
            to: Instant,
        ): List<DomainOxygenSaturationRecord> =
            withContext(ioDispatcher) {
                try {
                    readAllPages<OxygenSaturationRecord>(from, to).map { it.toDomain() }
                } catch (e: HealthConnectPermissionRevokedException) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                        "Oxygen saturation record permission not granted"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    app.readylytics.health.domain.util.logD("HealthConnectRepository") {
                        "Oxygen saturation record permission not granted"
                    }
                    emptyList()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading oxygen saturation records"
                    }
                    emptyList()
                }
            }

        private suspend fun <T> readOrEmpty(block: suspend () -> List<T>): List<T> =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }

        override suspend fun discoverDevices(windowDays: Int): List<String> =
            withContext(ioDispatcher) {
                try {
                    app.readylytics.health.domain.util.logD(
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
                        val hrRecordsDeferred =
                            async { readOrEmpty { readHeartRateSamples(from, to) } }
                        val hrvRecordsDeferred =
                            async { readOrEmpty { readHrvSamples(from, to) } }
                        val workoutRecordsDeferred =
                            async { readOrEmpty { readExerciseSessions(from, to) } }
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

                        sleepSessionsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }

                        hrRecordsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }

                        hrvRecordsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }

                        workoutRecordsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }

                        // Steps are frequently the only data the phone records, so scanning
                        // them here is what surfaces the phone as a selectable source device.
                        stepsRecordsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }

                        weightRecordsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }

                        bodyFatRecordsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }

                        bloodPressureRecordsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }

                        spo2RecordsDeferred.await().forEach { record ->
                            devices.add(record.deviceName)
                        }
                    }

                    app.readylytics.health.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "Device discovery found ${devices.size} unique devices" }
                    devices.sorted()
                } catch (e: SecurityException) {
                    throw HealthConnectPermissionRevokedException(e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.readylytics.health.domain.util.logE(
                        "HealthConnectRepository",
                        e,
                    ) { "Device discovery failed" }
                    emptyList()
                }
            }
    }
