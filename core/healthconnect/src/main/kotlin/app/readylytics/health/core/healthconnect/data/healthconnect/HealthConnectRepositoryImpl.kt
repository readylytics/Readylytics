package app.readylytics.health.core.healthconnect.data.healthconnect

import app.readylytics.health.core.model.domain.repository.ExerciseSessionRead
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
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
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.PermissionStatus
import app.readylytics.health.core.model.domain.repository.ReadRetryScope
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectRepositoryImpl
    @Inject
    constructor(
        private val availability: HealthConnectAvailability,
        private val permissions: HealthConnectPermissionGateway,
        private val vitals: HealthConnectVitalsReader,
        private val bodyMetrics: HealthConnectBodyMetricsReader,
        private val exercise: HealthConnectExerciseReader,
        private val stepRecordReader: StepRecordReader,
        private val deviceDiscovery: HealthConnectDeviceDiscovery,
    ) : HealthConnectRepository {
        override val criticalPermissions: Set<String>
            get() = permissions.criticalPermissions

        override fun isHistoryReadAvailable(): Boolean = availability.isHistoryReadAvailable()

        override fun isBackgroundReadAvailable(): Boolean = availability.isBackgroundReadAvailable()

        override val requiredPermissions: Set<String>
            get() = permissions.requiredPermissions

        override val optionalPermissions: Set<String>
            get() = permissions.optionalPermissions

        override val allPermissions: Set<String>
            get() = permissions.allPermissions

        override val backgroundReadPermission: String
            get() = permissions.backgroundReadPermission

        override fun isAvailable(): Boolean = availability.isAvailable()

        override suspend fun checkPermissions(): PermissionStatus = permissions.checkPermissions()

        override suspend fun hasBodyTemperaturePermission(): Boolean =
            permissions.hasReadPermission(BodyTemperatureRecord::class, "body temperature")

        override suspend fun hasStepsPermission(): Boolean =
            permissions.hasReadPermission(StepsRecord::class, "steps")

        override suspend fun hasWeightPermission(): Boolean =
            permissions.hasReadPermission(WeightRecord::class, "weight")

        override suspend fun hasDistancePermission(): Boolean =
            permissions.hasReadPermission(DistanceRecord::class, "distance")

        override suspend fun hasBodyFatPermission(): Boolean =
            permissions.hasReadPermission(BodyFatRecord::class, "body fat")

        override suspend fun hasBloodPressurePermission(): Boolean =
            permissions.hasReadPermission(BloodPressureRecord::class, "blood pressure")

        override suspend fun hasOxygenSaturationPermission(): Boolean =
            permissions.hasReadPermission(OxygenSaturationRecord::class, "oxygen saturation")

        override suspend fun hasVo2MaxPermission(): Boolean =
            permissions.hasReadPermission(Vo2MaxRecord::class, "VO2 max")

        override suspend fun hasExerciseRoutesPermission(): Boolean =
            permissions.hasExerciseRoutesPermission()

        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainSleepSessionRecord>> =
            vitals.readSleepSessions(from, to, retryScope)

        override suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainHeartRateRecord>> =
            vitals.readHeartRateSamples(from, to, retryScope)

        override suspend fun readHrvSamples(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainHrvRecord>> =
            vitals.readHrvSamples(from, to, retryScope)

        override suspend fun readHeartRateSamplesPaged(
            from: Instant,
            to: Instant,
            startPageToken: String?,
            retryScope: ReadRetryScope?,
            onPage: suspend (List<DomainHeartRateRecord>, String?) -> Unit,
        ): ReadOutcome<Unit> =
            vitals.readHeartRateSamplesPaged(from, to, startPageToken, retryScope, onPage)

        override suspend fun readHrvSamplesPaged(
            from: Instant,
            to: Instant,
            startPageToken: String?,
            retryScope: ReadRetryScope?,
            onPage: suspend (List<DomainHrvRecord>, String?) -> Unit,
        ): ReadOutcome<Unit> =
            vitals.readHrvSamplesPaged(from, to, startPageToken, retryScope, onPage)

        override suspend fun readExerciseSessions(
            from: Instant,
            to: Instant,
            includeDetails: Boolean,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainExerciseSessionRecord>> =
            exercise.readExerciseSessions(from, to, includeDetails, retryScope)

        override suspend fun readExerciseSessionsWithCompletion(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ExerciseSessionRead = exercise.readExerciseSessionsWithCompletion(from, to, retryScope)

        override suspend fun readExerciseSession(id: String): DomainExerciseSessionRecord? =
            exercise.readExerciseSession(id)

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
            bodyMetrics.readWeightRecords(from, to, retryScope)

        override suspend fun readBodyFatRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBodyFatRecord>> =
            bodyMetrics.readBodyFatRecords(from, to, retryScope)

        override suspend fun readBloodPressureRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBloodPressureRecord>> =
            bodyMetrics.readBloodPressureRecords(from, to, retryScope)

        override suspend fun readOxygenSaturationRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainOxygenSaturationRecord>> =
            bodyMetrics.readOxygenSaturationRecords(from, to, retryScope)

        override suspend fun readBodyTemperatureRecords(
            from: Instant,
            to: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainBodyTemperatureRecord>> =
            bodyMetrics.readBodyTemperatureRecords(from, to, retryScope)

        override suspend fun readVo2MaxRecords(
            startTime: Instant,
            endTime: Instant,
            retryScope: ReadRetryScope?,
        ): ReadOutcome<List<DomainVo2MaxRecord>> =
            bodyMetrics.readVo2MaxRecords(startTime, endTime, retryScope)

        override suspend fun discoverDevices(windowDays: Int): List<String> =
            deviceDiscovery.discoverDevices(windowDays)
    }
