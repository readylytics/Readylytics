package com.gregor.lauritz.healthdashboard.data.healthconnect

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
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectPermissionRevokedException
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
        @ApplicationContext private val context: Context,
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

        private val client: HealthConnectClient by lazy {
            HealthConnectClient.getOrCreate(context)
        }

        override fun isAvailable(): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        override suspend fun checkPermissions(): PermissionStatus =
            withContext(Dispatchers.IO) {
                com.gregor.lauritz.healthdashboard.domain.util.logD(
                    "HealthConnectRepository",
                ) { "Checking permissions..." }
                if (!isAvailable()) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "SDK not available" }
                    return@withContext PermissionStatus.Unavailable
                }
                val granted =
                    try {
                        client.permissionController.getGrantedPermissions()
                    } catch (e: Exception) {
                        com.gregor.lauritz.healthdashboard.domain.util.logE("HealthConnectRepository", e) {
                            "Failed to get granted permissions"
                        }
                        throw e
                    }
                com.gregor.lauritz.healthdashboard.domain.util.logD(
                    "HealthConnectRepository",
                ) { "Granted permissions: $granted" }
                com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                    "Required permissions: $requiredPermissions"
                }

                if (granted.containsAll(requiredPermissions)) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "All required permissions granted" }
                    PermissionStatus.Granted
                } else {
                    val missing = requiredPermissions - granted
                    com.gregor.lauritz.healthdashboard.domain.util.logD(
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

        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
        ): List<SleepSessionRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<SleepSessionRecord>(from, to)
            }

        override suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
        ): List<HeartRateRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<HeartRateRecord>(from, to)
            }

        override suspend fun readRestingHeartRateSamples(
            from: Instant,
            to: Instant,
        ): List<RestingHeartRateRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<RestingHeartRateRecord>(from, to)
            }

        override suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
        ): List<HeartRateVariabilityRmssdRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<HeartRateVariabilityRmssdRecord>(from, to)
            }

        override suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
        ): List<ExerciseSessionRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<ExerciseSessionRecord>(from, to)
            }

        override suspend fun readStepsRecords(
            from: Instant,
            to: Instant,
        ): List<StepsRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<StepsRecord>(from, to)
            }

        override suspend fun readSteps(
            from: Instant,
            to: Instant,
        ): Long =
            withContext(Dispatchers.IO) {
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
            withContext(Dispatchers.IO) {
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
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE("HealthConnectRepository", e) {
                        "Error batch fetching steps"
                    }
                    emptyMap()
                }
            }

        override suspend fun readWeightRecords(
            from: Instant,
            to: Instant,
        ): List<WeightRecord> =
            withContext(Dispatchers.IO) {
                try {
                    readAllPages(from, to)
                } catch (e: HealthConnectPermissionRevokedException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                        "Weight record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                        "Weight record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading weight records"
                    }
                    emptyList()
                }
            }

        override suspend fun readBodyFatRecords(
            from: Instant,
            to: Instant,
        ): List<BodyFatRecord> =
            withContext(Dispatchers.IO) {
                try {
                    readAllPages(from, to)
                } catch (e: HealthConnectPermissionRevokedException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                        "Body fat record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                        "Body fat record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading body fat records"
                    }
                    emptyList()
                }
            }

        override suspend fun readBloodPressureRecords(
            from: Instant,
            to: Instant,
        ): List<BloodPressureRecord> =
            withContext(Dispatchers.IO) {
                try {
                    readAllPages(from, to)
                } catch (e: HealthConnectPermissionRevokedException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                        "Blood pressure record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                        "Blood pressure record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading blood pressure records"
                    }
                    emptyList()
                }
            }

        override suspend fun readOxygenSaturationRecords(
            from: Instant,
            to: Instant,
        ): List<OxygenSaturationRecord> =
            withContext(Dispatchers.IO) {
                try {
                    readAllPages(from, to)
                } catch (e: HealthConnectPermissionRevokedException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                        "Oxygen saturation record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: SecurityException) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") {
                        "Oxygen saturation record permission not granted: ${e.message}"
                    }
                    emptyList()
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE("HealthConnectRepository", e) {
                        "Error reading oxygen saturation records"
                    }
                    emptyList()
                }
            }

        override suspend fun discoverDevices(windowDays: Int): List<String> =
            withContext(Dispatchers.IO) {
                try {
                    com.gregor.lauritz.healthdashboard.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "Discovering devices in $windowDays day window..." }
                    val from = Instant.now().minusSeconds(windowDays.toLong() * TimeUnit.DAYS.toSeconds(1))
                    val to = Instant.now()

                    val devices = mutableSetOf<String>()

                    coroutineScope {
                        // Each read is wrapped so a single revoked/missing permission can't
                        // cancel the whole scope and collapse discovery to an empty list.
                        val sleepSessionsDeferred = async { runCatching { readSleepSessions(from, to) }.getOrDefault(emptyList()) }
                        val hrRecordsDeferred = async { runCatching { readHeartRateSamples(from, to) }.getOrDefault(emptyList()) }
                        val hrvRecordsDeferred = async { runCatching { readHrvSamples(from, to) }.getOrDefault(emptyList()) }
                        val workoutRecordsDeferred = async { runCatching { readExerciseSessions(from, to) }.getOrDefault(emptyList()) }
                        val stepsRecordsDeferred = async { runCatching { readStepsRecords(from, to) }.getOrDefault(emptyList()) }
                        val weightRecordsDeferred = async { runCatching { readWeightRecords(from, to) }.getOrDefault(emptyList()) }
                        val bodyFatRecordsDeferred = async { runCatching { readBodyFatRecords(from, to) }.getOrDefault(emptyList()) }
                        val bloodPressureRecordsDeferred = async { runCatching { readBloodPressureRecords(from, to) }.getOrDefault(emptyList()) }
                        val spo2RecordsDeferred = async { runCatching { readOxygenSaturationRecords(from, to) }.getOrDefault(emptyList()) }

                        sleepSessionsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }

                        hrRecordsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }

                        hrvRecordsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }

                        workoutRecordsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }

                        // Steps are frequently the only data the phone records, so scanning
                        // them here is what surfaces the phone as a selectable source device.
                        stepsRecordsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }

                        weightRecordsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }

                        bodyFatRecordsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }

                        bloodPressureRecordsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }

                        spo2RecordsDeferred.await().forEach { record ->
                            devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                        }
                    }

                    com.gregor.lauritz.healthdashboard.domain.util.logD(
                        "HealthConnectRepository",
                    ) { "Device discovery found ${devices.size} unique devices" }
                    devices.sorted()
                } catch (e: SecurityException) {
                    throw HealthConnectPermissionRevokedException(e)
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE(
                        "HealthConnectRepository",
                        e,
                    ) { "Device discovery failed" }
                    emptyList()
                }
            }
    }
