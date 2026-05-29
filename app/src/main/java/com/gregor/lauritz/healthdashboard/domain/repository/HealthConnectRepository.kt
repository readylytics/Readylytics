package com.gregor.lauritz.healthdashboard.domain.repository

import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
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

    fun isAvailable(): Boolean

    suspend fun checkPermissions(): PermissionStatus

    suspend fun readSleepSessions(
        from: Instant,
        to: Instant,
    ): List<SleepSessionRecord>

    suspend fun readHeartRateSamples(
        from: Instant,
        to: Instant,
    ): List<HeartRateRecord>

    suspend fun readRestingHeartRateSamples(
        from: Instant,
        to: Instant,
    ): List<RestingHeartRateRecord>

    suspend fun readHrvSamples(
        from: Instant,
        to: Instant,
    ): List<HeartRateVariabilityRmssdRecord>

    suspend fun readExerciseSessions(
        from: Instant,
        to: Instant,
    ): List<ExerciseSessionRecord>

    suspend fun readStepsRecords(
        from: Instant,
        to: Instant,
    ): List<StepsRecord>

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
    ): List<WeightRecord>

    suspend fun readBodyFatRecords(
        from: Instant,
        to: Instant,
    ): List<BodyFatRecord>

    suspend fun readBloodPressureRecords(
        from: Instant,
        to: Instant,
    ): List<BloodPressureRecord>
}
