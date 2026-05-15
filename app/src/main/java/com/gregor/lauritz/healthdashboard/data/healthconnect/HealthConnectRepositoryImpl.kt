package com.gregor.lauritz.healthdashboard.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectPermissionRevokedException
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import com.gregor.lauritz.healthdashboard.domain.util.logD
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

@Singleton
class HealthConnectRepositoryImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        // Lazy<> avoids a Dagger cycle: PermissionManager itself depends on HealthConnectRepository.
        private val permissionManager: Lazy<HealthConnectPermissionManager>,
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

        /**
         * Pages through Health Connect records of type [T]. Before issuing a read,
         * we consult the global [HealthConnectPermissionManager] to short-circuit
         * if we already know the user revoked this record type — that avoids
         * generating a SecurityException for every batch.
         *
         * If the read still throws SecurityException (race with system revocation
         * dialog), we record the revocation with the manager and surface a
         * descriptive [HealthConnectPermissionRevokedException] instead of a
         * generic IOException.
         */
        private suspend fun <T : Record> readAllPages(
            recordClass: KClass<T>,
            from: Instant,
            to: Instant,
        ): List<T> {
            val permissionString = HealthPermission.getReadPermission(recordClass)
            if (!permissionManager.get().hasPermission(permissionString)) {
                logD("HealthConnectRepository") {
                    "Skipping read for ${recordClass.simpleName}: permission already marked revoked"
                }
                throw HealthConnectPermissionRevokedException(
                    SecurityException("Permission $permissionString known to be revoked"),
                )
            }
            val all = mutableListOf<T>()
            var pageToken: String? = null
            try {
                do {
                    val response =
                        client.readRecords(
                            ReadRecordsRequest(
                                recordType = recordClass,
                                timeRangeFilter = TimeRangeFilter.between(from, to),
                                pageToken = pageToken,
                            ),
                        )
                    all.addAll(response.records)
                    pageToken = response.pageToken
                } while (pageToken != null)
            } catch (e: SecurityException) {
                permissionManager.get().onPermissionRevoked(permissionString)
                throw HealthConnectPermissionRevokedException(e)
            }
            return all
        }

        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
        ): List<SleepSessionRecord> =
            withContext(Dispatchers.IO) {
                readAllPages(SleepSessionRecord::class, from, to)
            }

        override suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
        ): List<HeartRateRecord> =
            withContext(Dispatchers.IO) {
                readAllPages(HeartRateRecord::class, from, to)
            }

        override suspend fun readRestingHeartRateSamples(
            from: Instant,
            to: Instant,
        ): List<RestingHeartRateRecord> =
            withContext(Dispatchers.IO) {
                readAllPages(RestingHeartRateRecord::class, from, to)
            }

        override suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
        ): List<HeartRateVariabilityRmssdRecord> =
            withContext(Dispatchers.IO) {
                readAllPages(HeartRateVariabilityRmssdRecord::class, from, to)
            }

        override suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
        ): List<ExerciseSessionRecord> =
            withContext(Dispatchers.IO) {
                readAllPages(ExerciseSessionRecord::class, from, to)
            }

        override suspend fun readSteps(
            from: Instant,
            to: Instant,
        ): Long =
            withContext(Dispatchers.IO) {
                val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
                if (!permissionManager.get().hasPermission(stepsPermission)) {
                    throw HealthConnectPermissionRevokedException(
                        SecurityException("Permission $stepsPermission known to be revoked"),
                    )
                }
                try {
                    val result =
                        client.aggregate(
                            AggregateRequest(
                                metrics = setOf(StepsRecord.COUNT_TOTAL),
                                timeRangeFilter = TimeRangeFilter.between(from, to),
                            ),
                        )
                    result[StepsRecord.COUNT_TOTAL] ?: 0L
                } catch (e: SecurityException) {
                    permissionManager.get().onPermissionRevoked(stepsPermission)
                    throw HealthConnectPermissionRevokedException(e)
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

                    val sleepSessions = readSleepSessions(from, to)
                    sleepSessions.forEach { record ->
                        devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                    }

                    val hrRecords = readHeartRateSamples(from, to)
                    hrRecords.forEach { record ->
                        devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                    }

                    val hrvRecords = readHrvSamples(from, to)
                    hrvRecords.forEach { record ->
                        devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
                    }

                    val workoutRecords = readExerciseSessions(from, to)
                    workoutRecords.forEach { record ->
                        devices.add(DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin))
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
