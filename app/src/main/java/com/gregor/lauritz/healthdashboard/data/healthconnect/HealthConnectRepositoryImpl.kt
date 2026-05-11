package com.gregor.lauritz.healthdashboard.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectPermissionRevokedException
import com.gregor.lauritz.healthdashboard.domain.repository.HealthConnectRepository
import com.gregor.lauritz.healthdashboard.domain.repository.PermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
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

        override val requiredPermissions: Set<String> = criticalPermissions +
            setOf("android.permission.health.READ_HEALTH_DATA_HISTORY")

        private val client: HealthConnectClient by lazy {
            HealthConnectClient.getOrCreate(context)
        }

        override fun isAvailable(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        override suspend fun checkPermissions(): PermissionStatus =
            withContext(Dispatchers.IO) {
                com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") { "Checking permissions..." }
                if (!isAvailable()) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") { "SDK not available" }
                    return@withContext PermissionStatus.Unavailable
                }
                val granted = try {
                    client.permissionController.getGrantedPermissions()
                } catch (e: Exception) {
                    com.gregor.lauritz.healthdashboard.domain.util.logE("HealthConnectRepository", e) { "Failed to get granted permissions" }
                    throw e
                }
                com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") { "Granted permissions: $granted" }
                com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") { "Required permissions: $requiredPermissions" }

                if (granted.containsAll(requiredPermissions)) {
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") { "All required permissions granted" }
                    PermissionStatus.Granted
                } else {
                    val missing = requiredPermissions - granted
                    com.gregor.lauritz.healthdashboard.domain.util.logD("HealthConnectRepository") { "Missing permissions: $missing" }
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
                    val response = client.readRecords(
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

        override suspend fun readSteps(
            from: Instant,
            to: Instant,
        ): Long =
            withContext(Dispatchers.IO) {
                val result = client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                    )
                )
                result[StepsRecord.COUNT_TOTAL] ?: 0L
            }
    }
