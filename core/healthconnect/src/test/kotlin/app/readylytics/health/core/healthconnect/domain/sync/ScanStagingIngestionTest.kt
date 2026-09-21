package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.DomainBloodPressureRecord
import app.readylytics.health.core.model.domain.model.DomainBodyFatRecord
import app.readylytics.health.core.model.domain.model.DomainBodyTemperatureRecord
import app.readylytics.health.core.model.domain.model.DomainExerciseSessionRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.DomainOxygenSaturationRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.DomainStepsRecord
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import app.readylytics.health.core.model.domain.model.DomainWeightRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.PermissionStatus
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.TypeScanState
import app.readylytics.health.core.model.domain.sync.stagedIds
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ScanStagingIngestionTest {
    private val staging = FakeScanStagingStore()
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)

    private fun coordinator(repo: HealthConnectRepository) =
        HealthIngestionCoordinator(repo, healthIngestionStore, staging)

    private fun prefs(): UserPreferences = UserPreferences()

    private fun hrPage(id: String, sampleCount: Int): DomainHeartRateRecord =
        DomainHeartRateRecord(
            id = id,
            deviceName = "Pixel Watch",
            samples = (0 until sampleCount).map {
                DomainHeartRateSample(
                    time = Instant.ofEpochMilli(it * 1000L),
                    beatsPerMinute = 70,
                )
            },
        )

    @Test
    fun everyHeartRatePageIdIsStagedAndTheTypeIsMarkedComplete() =
        runTest {
            val repo = FakeHealthConnectRepository(hrPages = listOf(hrPage("a", 3), hrPage("b", 3)))
            val coordinator = coordinator(repo)
            val scanId = ScanIdentity("run-a", "0")

            coordinator.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = scanId,
            )

            assertEquals(setOf("a", "b"), staging.stagedIds(scanId, HealthDataType.HEART_RATE))
            assertEquals(TypeScanState.COMPLETE, staging.stateOf(scanId, HealthDataType.HEART_RATE))
        }

    @Test
    fun deniedTypeIsNeverMarkedCompleteAndStagesNothing() =
        runTest {
            val repo = FakeHealthConnectRepository(hrOutcome = ReadOutcomeKind.DENIED)
            val coordinator = coordinator(repo)
            val scanId = ScanIdentity("run-a", "0")

            coordinator.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = scanId,
            )

            assertTrue(staging.stagedIds(scanId, HealthDataType.HEART_RATE).isEmpty())
            assertEquals(TypeScanState.SCANNING, staging.stateOf(scanId, HealthDataType.HEART_RATE))
        }

    @Test
    fun cancelledPageStreamLeavesScanIncompleteSoNothingIsPruned() =
        runTest {
            val repo = FakeHealthConnectRepository(hrPages = listOf(hrPage("a", 3)), failOnPage = 1)
            val coordinator = coordinator(repo)
            val scanId = ScanIdentity("run-a", "0")

            runCatching {
                coordinator.ingestWindow(
                    windowStart = Instant.ofEpochMilli(0),
                    windowEnd = Instant.ofEpochMilli(86_400_000),
                    prefs = prefs(),
                    scanIdentity = scanId,
                )
            }

            assertEquals(TypeScanState.SCANNING, staging.stateOf(scanId, HealthDataType.HEART_RATE))
        }

    @Test
    fun resumingKeepsPreviouslyStagedIdsAndForwardsTheStartToken() =
        runTest {
            val repo = FakeHealthConnectRepository(hrPages = listOf(hrPage("b", 3)))
            val coordinator = coordinator(repo)
            val scanId = ScanIdentity("run-a", "0")
            staging.beginTypeScan(scanId, HealthDataType.HEART_RATE, resume = false)
            staging.stageIds(scanId, HealthDataType.HEART_RATE, listOf("a"))

            coordinator.ingestWindow(
                windowStart = Instant.ofEpochMilli(0),
                windowEnd = Instant.ofEpochMilli(86_400_000),
                prefs = prefs(),
                scanIdentity = scanId,
                resumeHrScan = true,
                hrStartPageToken = "token-1",
            )

            assertEquals(setOf("a", "b"), staging.stagedIds(scanId, HealthDataType.HEART_RATE))
            assertEquals("token-1", repo.observedHrStartToken)
        }

    enum class ReadOutcomeKind {
        AVAILABLE,
        DENIED,
        UNSUPPORTED,
    }

    private class FakeHealthConnectRepository(
        val hrPages: List<DomainHeartRateRecord> = emptyList(),
        val hrOutcome: ReadOutcomeKind = ReadOutcomeKind.AVAILABLE,
        val failOnPage: Int? = null,
    ) : HealthConnectRepository {
        var observedHrStartToken: String? = null

        override val criticalPermissions: Set<String> = emptySet()
        override val requiredPermissions: Set<String> = emptySet()
        override val optionalPermissions: Set<String> = emptySet()
        override val allPermissions: Set<String> = emptySet()
        override val backgroundReadPermission: String = ""

        override fun isAvailable(): Boolean = true
        override suspend fun checkPermissions(): PermissionStatus = PermissionStatus.Granted
        override suspend fun hasBodyTemperaturePermission(): Boolean = true
        override suspend fun hasStepsPermission(): Boolean = true
        override suspend fun hasWeightPermission(): Boolean = true
        override suspend fun hasDistancePermission(): Boolean = true
        override suspend fun hasBodyFatPermission(): Boolean = true
        override suspend fun hasBloodPressurePermission(): Boolean = true
        override suspend fun hasOxygenSaturationPermission(): Boolean = true
        override suspend fun hasExerciseRoutesPermission(): Boolean = true
        override suspend fun hasVo2MaxPermission(): Boolean = false

        override suspend fun readSleepSessions(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainSleepSessionRecord>> = ReadOutcome.Available(emptyList())

        override suspend fun readHeartRateSamples(
            from: Instant,
            to: Instant,
        ): ReadOutcome<List<DomainHeartRateRecord>> = ReadOutcome.Available(emptyList())

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
            observedHrStartToken = startPageToken
            return when (hrOutcome) {
                ReadOutcomeKind.DENIED -> ReadOutcome.Denied
                ReadOutcomeKind.UNSUPPORTED -> ReadOutcome.Unsupported
                ReadOutcomeKind.AVAILABLE -> {
                    for ((index, page) in hrPages.withIndex()) {
                        if (failOnPage != null && index >= failOnPage) {
                            error("Simulated cancellation at page $index")
                        }
                        val nextToken = if (index + 1 < hrPages.size) "token-${index + 1}" else null
                        onPage(listOf(page), nextToken)
                    }
                    if (failOnPage != null && hrPages.size >= failOnPage) {
                        error("Simulated cancellation after pages at $failOnPage")
                    }
                    ReadOutcome.Available(Unit)
                }
            }
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

        override suspend fun discoverDevices(windowDays: Int): List<String> = emptyList()

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
}
