package app.readylytics.health.benchmark

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
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.PermissionStatus
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.security.EncryptionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BenchmarkFakeSettingsRepository(
    initial: UserPreferences,
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val userPreferences: Flow<UserPreferences> = state

    override suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean) = Unit

    override suspend fun updateMaxHeartRate(bpm: Int) = Unit

    override suspend fun migrateDeviceSelectionIfNeeded() = Unit

    override suspend fun updateLastSyncTimestamp(timestamp: Long) = Unit

    override suspend fun updateBirthday(date: LocalDate) = Unit

    override suspend fun updateScoringVersion(version: Int) = Unit

    override suspend fun updateSleepScoreRecalcBaseline(
        weightProfile: SleepScoreWeightProfile,
        goalSleepHours: Float,
        hypersomniaOnsetPercent: Int,
    ) = Unit
}

class BenchmarkFakeEncryptionManager : EncryptionManager {
    override fun encrypt(plaintext: String): String = plaintext

    override fun decrypt(ciphertext: String): String? = ciphertext
}

class BenchmarkFakeHealthConnectRepository(
    var pagesSequence: Sequence<List<DomainHeartRateRecord>> = emptySequence(),
) : HealthConnectRepository {
    override val criticalPermissions: Set<String> = emptySet()
    override val requiredPermissions: Set<String> = emptySet()
    override val optionalPermissions: Set<String> = emptySet()
    override val allPermissions: Set<String> = emptySet()
    override val backgroundReadPermission: String = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    override fun isAvailable(): Boolean = true

    override suspend fun checkPermissions(): PermissionStatus = PermissionStatus.Granted

    override fun hasPermission(permission: String): Boolean = true

    override fun hasAllPermissions(permissions: Set<String>): Boolean = true

    override suspend fun readSleepSessions(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainSleepSessionRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readHeartRateSamples(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainHeartRateRecord>> = ReadOutcome.Available(pagesSequence.flatten().toList())

    override suspend fun readHrvSamples(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainHrvRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readHeartRateSamplesPaged(
        from: Instant,
        to: Instant,
        startPageToken: String?,
        onPage: suspend (records: List<DomainHeartRateRecord>, nextPageToken: String?) -> Unit,
    ): ReadOutcome<Unit> {
        val pages = pagesSequence.toList()
        for (i in pages.indices) {
            val nextToken = if (i < pages.size - 1) "token_${i + 1}" else null
            onPage(pages[i], nextToken)
        }
        return ReadOutcome.Available(Unit)
    }

    override suspend fun readHrvSamplesPaged(
        from: Instant,
        to: Instant,
        startPageToken: String?,
        onPage: suspend (records: List<DomainHrvRecord>, nextPageToken: String?) -> Unit,
    ): ReadOutcome<Unit> = ReadOutcome.Available(Unit)

    override suspend fun readExerciseSessions(
        from: Instant,
        to: Instant,
        includeDetails: Boolean,
    ): ReadOutcome<List<DomainExerciseSessionRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readStepsRecords(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainStepsRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readSteps(
        from: Instant,
        to: Instant,
    ): ReadOutcome<Long> = ReadOutcome.Available(0L)

    override suspend fun readDailyStepTotals(
        from: Instant,
        to: Instant,
        zoneId: ZoneId,
    ): ReadOutcome<Map<LocalDate, Long>> = ReadOutcome.Available(emptyMap())

    override suspend fun discoverDevices(windowDays: Int): List<String> =
        listOf("fixture-origin-0", "fixture-origin-1", "fixture-origin-2")

    override suspend fun readWeightRecords(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainWeightRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readBodyFatRecords(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainBodyFatRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readBloodPressureRecords(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainBloodPressureRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readOxygenSaturationRecords(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainOxygenSaturationRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readBodyTemperatureRecords(
        from: Instant,
        to: Instant,
    ): ReadOutcome<List<DomainBodyTemperatureRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readVo2MaxRecords(
        startTime: Instant,
        endTime: Instant,
    ): ReadOutcome<List<DomainVo2MaxRecord>> = ReadOutcome.Available(emptyList())

    override suspend fun readExerciseSession(id: String): DomainExerciseSessionRecord? = null
}
