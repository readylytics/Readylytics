package com.gregor.lauritz.healthdashboard.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PermissionStatus {
    data object Granted : PermissionStatus

    data object Unavailable : PermissionStatus

    data class Missing(
        val missing: Set<String>,
    ) : PermissionStatus
}

@Singleton
class HealthConnectRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val requiredPermissions: Set<String> =
            setOf(
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                HealthPermission.getReadPermission(ExerciseSessionRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class),
            )

        val client: HealthConnectClient by lazy {
            HealthConnectClient.getOrCreate(context)
        }

        fun isAvailable(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        suspend fun checkPermissions(): PermissionStatus =
            withContext(Dispatchers.IO) {
                if (!isAvailable()) return@withContext PermissionStatus.Unavailable
                val granted = client.permissionController.getGrantedPermissions()
                if (granted.containsAll(requiredPermissions)) {
                    PermissionStatus.Granted
                } else {
                    PermissionStatus.Missing(requiredPermissions - granted)
                }
            }

        private suspend inline fun <reified T : androidx.health.connect.client.records.Record> readAllPages(
            from: Instant,
            to: Instant,
        ): List<T> {
            val all = mutableListOf<T>()
            var pageToken: String? = null
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
            return all
        }

        suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
        ): List<SleepSessionRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<SleepSessionRecord>(from, to)
            }

        suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
        ): List<HeartRateRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<HeartRateRecord>(from, to)
            }

        suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
        ): List<HeartRateVariabilityRmssdRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<HeartRateVariabilityRmssdRecord>(from, to)
            }

        suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
        ): List<ExerciseSessionRecord> =
            withContext(Dispatchers.IO) {
                readAllPages<ExerciseSessionRecord>(from, to)
            }

        suspend fun readSteps(
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
