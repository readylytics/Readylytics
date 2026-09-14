package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.DomainSleepSessionRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.isComplete
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

class CompleteTypeScanTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = InMemoryResyncCheckpointStore()
    private val transactionRunner = RecordingTransactionRunner()
    private val fakeStore = FakeScanHealthIngestionStore()

    private val fixedClock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC"))
    private lateinit var useCase: ResyncRangeUseCase

    @Before
    fun setup() {
        fakeStore.clear()
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coEvery { changeSynchronizer.captureChangesTokens() } returns mapOf(HealthDataType.HEART_RATE to "hr-base")
        coEvery { changeSynchronizer.commitTokens(any()) } returns Unit
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any()) } returns
            WalkForwardTrimpContext(TreeMap(), TreeMap())
        coEvery { scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any()) } returns
            WalkForwardBaselineContext(emptyList())
        coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any()) } returns
            WalkForwardFatigueContext(emptyList())
        coEvery { hcRepo.readSleepSessions(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readExerciseSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readDailyStepTotals(any(), any(), any()) } returns ReadOutcome.Available(emptyMap())
        coEvery { hcRepo.readWeightRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyFatRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBloodPressureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns ReadOutcome.Available(emptyList())

        useCase =
            ResyncRangeUseCase(
                settingsRepo = settingsRepo,
                clock = fixedClock,
                sessionLinkReconciler = sessionLinkReconciler,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
                healthIngestionStore = fakeStore,
                ingestion =
                    ResyncIngestionDependencies(
                        ingestionCoordinator = HealthIngestionCoordinator(hcRepo, fakeStore),
                        stepCountFetcher = StepCountFetcher(hcRepo),
                    ),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    @Test
    fun `only complete available scans authorize absence`() {
        assertTrue(ReadOutcome.Available(Unit).isComplete())
        assertFalse(ReadOutcome.Denied.isComplete())
        assertFalse(ReadOutcome.Unsupported.isComplete())
    }

    @Test
    fun `HR page interruption and restart replays chunk from beginning converging on deletion`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            // Seed A, B, C in store
            fakeStore.heartRateSamples["hr-A"] = createHrInput("hr-A", "2026-06-02T10:00:00Z")
            fakeStore.heartRateSamples["hr-B"] = createHrInput("hr-B", "2026-06-02T11:00:00Z")
            fakeStore.heartRateSamples["hr-C"] = createHrInput("hr-C", "2026-06-02T12:00:00Z")

            // HC provider has A (page 1) then C (page 2). B was deleted in HC.
            val page1 = listOf(createHrRecord("hr-A", "2026-06-02T10:00:00Z"))
            val page2 = listOf(createHrRecord("hr-C", "2026-06-02T12:00:00Z"))

            // First run: reads page 1, saves checkpoint with hrPageToken, then interrupts
            var callCount = 0
            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
                callCount++
                val token = invocation.args[2] as String?
                val onPage = invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                if (callCount == 1) {
                    onPage(page1, "page-token-2")
                    error("Simulated interruption after page 1 commit")
                } else {
                    assertEquals("Interrupted scan must replay with null startPageToken", null, token)
                    onPage(page1, "page-token-2")
                    onPage(page2, null)
                    ReadOutcome.Available(Unit)
                }
            }

            // Run 1 fails
            val result1 = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertFalse(result1.isSuccess)

            // Checkpoint was saved with midstream token
            val savedCheckpoint = checkpointStore.value
            assertNotNull(savedCheckpoint)
            assertEquals("page-token-2", savedCheckpoint?.hrPageToken)

            // Restart run 2 with saved checkpoint
            val result2 = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertTrue("Second run must succeed", result2.isSuccess)

            // Expected final IDs: A and C. B was deleted!
            assertEquals(setOf("hr-A", "hr-C"), fakeStore.heartRateSamples.keys)
        }

    @Test
    fun `denial after page A leaves B and C untouched until a later complete scan`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            // Seed A, B, C
            fakeStore.heartRateSamples["hr-A"] = createHrInput("hr-A", "2026-06-02T10:00:00Z")
            fakeStore.heartRateSamples["hr-B"] = createHrInput("hr-B", "2026-06-02T11:00:00Z")
            fakeStore.heartRateSamples["hr-C"] = createHrInput("hr-C", "2026-06-02T12:00:00Z")

            // HC returns page 1 (A), then Denied on second call / stream
            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Denied

            val result = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertTrue(result.isSuccess)

            // Since HR was Denied, absence is unauthorized: B and C must remain untouched!
            assertTrue("hr-B must remain", fakeStore.heartRateSamples.containsKey("hr-B"))
            assertTrue("hr-C must remain", fakeStore.heartRateSamples.containsKey("hr-C"))
            assertEquals(setOf("hr-A", "hr-B", "hr-C"), fakeStore.heartRateSamples.keys)
        }

    @Test
    fun `HRV page interruption and restart replays chunk from beginning converging on deletion`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            // Seed A, B, C in store
            fakeStore.hrvSamples["hrv-A"] = createHrvInput("hrv-A", "2026-06-02T10:00:00Z")
            fakeStore.hrvSamples["hrv-B"] = createHrvInput("hrv-B", "2026-06-02T11:00:00Z")
            fakeStore.hrvSamples["hrv-C"] = createHrvInput("hrv-C", "2026-06-02T12:00:00Z")

            val page1 = listOf(createHrvRecord("hrv-A", "2026-06-02T10:00:00Z"))
            val page2 = listOf(createHrvRecord("hrv-C", "2026-06-02T12:00:00Z"))

            var callCount = 0
            coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } coAnswers {
                callCount++
                val token = invocation.args[2] as String?
                val onPage = invocation.args[3] as suspend (List<DomainHrvRecord>, String?) -> Unit
                if (callCount == 1) {
                    onPage(page1, "hrv-token-2")
                    error("Simulated interruption after page 1 HRV")
                } else {
                    assertEquals("Interrupted HRV scan must replay with null startPageToken", null, token)
                    onPage(page1, "hrv-token-2")
                    onPage(page2, null)
                    ReadOutcome.Available(Unit)
                }
            }

            val result1 = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertFalse(result1.isSuccess)

            assertEquals("hrv-token-2", checkpointStore.value?.hrvPageToken)

            val result2 = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertTrue(result2.isSuccess)

            assertEquals(setOf("hrv-A", "hrv-C"), fakeStore.hrvSamples.keys)
        }

    @Test
    fun `zero-sample parent record ID is collected in CompleteTypeScan ids`() =
        runTest {
            val startDate = LocalDate.of(2026, 6, 1)
            val endDate = LocalDate.of(2026, 6, 5)

            // Seed an authoritative record for zero-sample-parent
            fakeStore.heartRateSamples["zero-parent"] = createHrInput("zero-parent", "2026-06-02T10:00:00Z")

            val zeroSampleRecord =
                DomainHeartRateRecord(
                    id = "zero-parent",
                    deviceName = "Watch",
                    samples = emptyList(),
                    startTime = Instant.parse("2026-06-02T10:00:00Z"),
                    endTime = Instant.parse("2026-06-02T10:05:00Z"),
                )

            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val onPage = invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                onPage(listOf(zeroSampleRecord), null)
                ReadOutcome.Available(Unit)
            }

            val result = useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertTrue(result.isSuccess)

            // The zero-sample parent was in HC, so its ID was collected and it was NOT deleted
            assertTrue("zero-parent must be preserved", fakeStore.heartRateSamples.containsKey("zero-parent"))
        }

    private fun createHrInput(id: String, isoTime: String) =
        HeartRateInput(
            id = id,
            timestampMs = Instant.parse(isoTime).toEpochMilli(),
            beatsPerMinute = 70,
            recordType = "HEART_RATE",
            sessionId = null,
            deviceName = "Watch",
        )

    private fun createHrRecord(id: String, isoTime: String) =
        DomainHeartRateRecord(
            id = id,
            deviceName = "Watch",
            samples = listOf(DomainHeartRateSample(Instant.parse(isoTime), 70)),
            startTime = Instant.parse(isoTime),
            endTime = Instant.parse(isoTime).plusSeconds(60),
        )

    private fun createHrvInput(id: String, isoTime: String) =
        HrvInput(
            id = id,
            timestampMs = Instant.parse(isoTime).toEpochMilli(),
            rmssdMs = 50f,
            recordType = "HRV",
            sessionId = null,
            deviceName = "Watch",
        )

    private fun createHrvRecord(id: String, isoTime: String) =
        DomainHrvRecord(
            id = id,
            time = Instant.parse(isoTime),
            rmssdMs = 50f,
            deviceName = "Watch",
        )

    private class FakeScanHealthIngestionStore : HealthIngestionStore {
        val heartRateSamples = mutableMapOf<String, HeartRateInput>()
        val hrvSamples = mutableMapOf<String, HrvInput>()

        fun clear() {
            heartRateSamples.clear()
            hrvSamples.clear()
        }

        override suspend fun persist(batch: HealthIngestionBatch) = Unit

        override suspend fun replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>) {
            sources.forEach { source ->
                source.rows.forEach { heartRateSamples[it.id] = it }
            }
        }

        override suspend fun replaceHrvSources(sources: List<SourcePayload<HrvInput>>) {
            sources.forEach { source ->
                source.rows.forEach { hrvSamples[it.id] = it }
            }
        }

        override suspend fun clearFrozenBaselines(start: LocalDate, endExclusive: LocalDate, zoneId: ZoneId) = Unit
        override suspend fun countHeartRateInRange(startMs: Long, endMs: Long): Int = heartRateSamples.size
        override suspend fun countHrvInRange(startMs: Long, endMs: Long): Int = hrvSamples.size
        override suspend fun countSleepSessionsInRange(startMs: Long, endMs: Long): Int = 0
        override suspend fun countWorkoutsInRange(startMs: Long, endMs: Long): Int = 0

        override suspend fun persistSingleWorkoutRoute(
            workoutId: String,
            routePoints: List<WorkoutRoutePoint>,
            routeState: String,
            totalDistanceMeters: Float?,
            avgSpeedKmh: Float?,
            elevationGainMeters: Float?,
        ) = Unit

        override suspend fun reconcileWindow(
            scan: CompleteTypeScan,
            zoneId: ZoneId,
        ): ScoreInvalidation.AffectedRange? =
            when (scan.type) {
                HealthDataType.HEART_RATE -> {
                    val toDelete = heartRateSamples.keys.filter { it !in scan.ids }
                    toDelete.forEach { heartRateSamples.remove(it) }
                    if (toDelete.isNotEmpty()) {
                        ScoreInvalidation.AffectedRange(
                            start = Instant.ofEpochMilli(scan.windowStartMs).atZone(zoneId).toLocalDate(),
                            endInclusive = Instant.ofEpochMilli(scan.windowEndExclusiveMs).atZone(zoneId).toLocalDate(),
                        )
                    } else {
                        null
                    }
                }
                HealthDataType.HRV -> {
                    val toDelete = hrvSamples.keys.filter { it !in scan.ids }
                    toDelete.forEach { hrvSamples.remove(it) }
                    if (toDelete.isNotEmpty()) {
                        ScoreInvalidation.AffectedRange(
                            start = Instant.ofEpochMilli(scan.windowStartMs).atZone(zoneId).toLocalDate(),
                            endInclusive = Instant.ofEpochMilli(scan.windowEndExclusiveMs).atZone(zoneId).toLocalDate(),
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
    }
}
