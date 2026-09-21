package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.sync.CompleteTypeScan
import app.readylytics.health.core.model.domain.sync.HealthIngestionBatch
import app.readylytics.health.core.model.domain.sync.HealthIngestionStore
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.InMemoryScanStagingStore
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncCheckpointStore
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.core.model.domain.sync.stagedIds
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

class PagedIngestResumptionTest {
    private val hcRepo = mockk<HealthConnectRepository>(relaxed = true)
    private val healthIngestionStore = mockk<HealthIngestionStore>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringRepository = mockk<ScoringRepository>(relaxed = true)
    private val sessionLinkReconciler = mockk<SessionLinkReconciler>(relaxed = true)
    private val changeSynchronizer = mockk<HealthChangeSynchronizer>(relaxed = true)
    private val selectedSourcePruner = mockk<SelectedSourcePruner>(relaxed = true)
    private val checkpointStore = InMemoryResyncCheckpointStore()
    private val baselineTokens = mapOf(HealthDataType.SLEEP to "baseline-sleep-token")
    private val transactionRunner = RecordingTransactionRunner()

    private lateinit var useCase: ResyncRangeUseCase

    @Before
    fun setup() {
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { changeSynchronizer.applyPendingChanges() } returns HealthChangeSyncOutcome(emptySet(), false)
        coEvery { changeSynchronizer.captureChangesTokens() } returns baselineTokens
        coEvery { changeSynchronizer.commitTokens(any()) } returns Unit
        coEvery { scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any()) } returns
            WalkForwardTrimpContext(TreeMap(), TreeMap())
        coEvery { scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any()) } returns
            WalkForwardBaselineContext(emptyList())
        coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any()) } returns
            WalkForwardFatigueContext(emptyList())
        coEvery { hcRepo.readSleepSessions(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readExerciseSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readWeightRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyFatRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBloodPressureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readOxygenSaturationRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readBodyTemperatureRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readVo2MaxRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readDailyStepTotals(any(), any(), any()) } returns ReadOutcome.Available(emptyMap())
        coEvery { hcRepo.readSteps(any(), any()) } returns ReadOutcome.Available(0L)
        coEvery { hcRepo.readHeartRateSamples(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHrvSamples(any(), any()) } returns ReadOutcome.Available(emptyList())

        useCase =
            ResyncRangeUseCase(
                settingsRepo = settingsRepo,
                clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
                sessionLinkReconciler = sessionLinkReconciler,
                changeSynchronizer = changeSynchronizer,
                selectedSourcePruner = selectedSourcePruner,
                checkpointStore = checkpointStore,
                healthIngestionStore = healthIngestionStore,
                ingestion =
                    ResyncIngestionDependencies(
                        ingestionCoordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore),
                        stepCountFetcher = StepCountFetcher(hcRepo),
                    ),
                recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                ioDispatcher = Dispatchers.Unconfined,
            )
    }

    @Test
    fun `page 1 of 3 HR reads updates checkpoint with hrPageToken`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            val savedTokens = mutableListOf<String?>()
            checkpointStore.onSave = {
                savedTokens.add(checkpointStore.value?.hrPageToken)
            }

            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), "page-2")
                callback(listOf(mockk(relaxed = true)), "page-3")
                callback(listOf(mockk(relaxed = true)), null)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            // Verify that intermediate checkpoints recorded page-2 and page-3
            assertEquals(true, savedTokens.contains("page-2"))
            assertEquals(true, savedTokens.contains("page-3"))
        }

    @Test
    fun `resuming ingestion with hrStartPageToken forwards stored token`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)
            val runIdentity = createRunIdentity(startDate, endDate)

            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = startDate,
                    selectionHash = runIdentity.scoringSnapshotId,
                    baselineChangeTokens = baselineTokens,
                    hrPageToken = "page-2",
                    runIdentity = runIdentity,
                )

            val capturedStartToken = slot<String?>()
            coEvery {
                hcRepo.readHeartRateSamplesPaged(any(), any(), captureNullable(capturedStartToken), any())
            } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), null)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals("page-2", capturedStartToken.captured)
        }

    @Test
    fun `resuming with hrvStartPageToken forwards stored token`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)
            val runIdentity = createRunIdentity(startDate, endDate)

            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = startDate,
                    selectionHash = runIdentity.scoringSnapshotId,
                    baselineChangeTokens = baselineTokens,
                    hrPageToken = null,
                    hrvPageToken = "hrv-page-2",
                    runIdentity = runIdentity,
                )

            val capturedHrvStartToken = slot<String?>()
            coEvery {
                hcRepo.readHrvSamplesPaged(any(), any(), captureNullable(capturedHrvStartToken), any())
            } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHrvRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), null)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals("hrv-page-2", capturedHrvStartToken.captured)
        }

    @Test
    fun `chunk timeout clears active page tokens`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 10)

            val savedCheckpoints = mutableListOf<ResyncCheckpoint>()
            checkpointStore.onSave = {
                checkpointStore.value?.let { savedCheckpoints.add(it) }
            }

            var callCount = 0
            coEvery { hcRepo.readSleepSessions(any(), any()) } coAnswers {
                callCount++
                if (callCount == 1) {
                    throw HealthConnectWindowTimeoutException(
                        Instant.EPOCH,
                        Instant.EPOCH.plusSeconds(3600),
                        RuntimeException("timeout"),
                    )
                }
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(emptyList())
            }

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 10, onProgress = null)

            // The checkpoint saved upon timeout must have cleared page tokens
            val timeoutCheckpoint = savedCheckpoints.first { it.chunkDaysOverride != null }
            assertNull(timeoutCheckpoint.hrPageToken)
            assertNull(timeoutCheckpoint.hrvPageToken)
            assertEquals(5, timeoutCheckpoint.chunkDaysOverride)
        }

    @Test
    fun `chunk completion clears active page tokens and advances to next phase`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            // After entire resync completes, checkpoint is cleared
            assertNull(checkpointStore.value)
        }

    @Test
    fun `HealthIngestionCoordinator notifies onTokenUpdated per streamed page`() =
        runTest {
            val coordinator = HealthIngestionCoordinator(hcRepo, healthIngestionStore)
            val windowStart = Instant.parse("2024-06-01T00:00:00Z")
            val windowEnd = Instant.parse("2024-06-02T00:00:00Z")

            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), "hr-token-1")
                callback(listOf(mockk(relaxed = true)), null)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            }
            coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHrvRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true)), "hrv-token-1")
                callback(listOf(mockk(relaxed = true)), null)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            }

            val tokenEvents = mutableListOf<Pair<String?, String?>>()
            coordinator.ingestWindow(
                windowStart = windowStart,
                windowEnd = windowEnd,
                prefs = UserPreferences(),
                onTokenUpdated = { hrToken, hrvToken ->
                    tokenEvents.add(hrToken to hrvToken)
                },
            )

            assertEquals(
                listOf(
                    "hr-token-1" to null,
                    null to null,
                    null to "hrv-token-1",
                    null to null,
                ),
                tokenEvents,
            )
        }

    @Test
    fun `kill after page 1 HR and resume matches uninterrupted run`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)
            val (samplePreBaseline, sample1) = createTestHeartRateInputs()
            val (record1, record2) = createTestDomainRecords()

            val staging = InMemoryScanStagingStore()
            val trackingStore = ResumptionTrackingStore(staging)
            trackingStore.heartRateSamples[samplePreBaseline.id] = samplePreBaseline
            trackingStore.heartRateSamples[sample1.id] = sample1

            val testUseCase = createResyncUseCase(trackingStore, staging, checkpointStore)
            mockResumedHeartRatePages(record1, record2)

            val run1Result =
                testUseCase.run(
                    startDate = startDate,
                    endDate = endDate,
                    chunkDays = 30,
                    onProgress = null,
                )
            assertFalse(run1Result.isSuccess)
            assertEquals("token-page-2", checkpointStore.value?.hrPageToken)

            val run2Result =
                testUseCase.run(
                    startDate = startDate,
                    endDate = endDate,
                    chunkDays = 30,
                    onProgress = null,
                )
            assertTrue(run2Result.isSuccess)

            val uninterruptedSourceIds =
                runUninterruptedRun(
                    startDate = startDate,
                    endDate = endDate,
                    records = listOf(record1, record2),
                    initialSamples = listOf(samplePreBaseline, sample1),
                )

            assertEquals(
                "Resumed run must end with exact same sourceIds as uninterrupted run",
                uninterruptedSourceIds,
                trackingStore.heartRateSamples.values.map { it.sourceId }.toSet(),
            )
            assertFalse(
                "Pre-baseline deleted record must be removed",
                trackingStore.heartRateSamples.values.any { it.sourceId == "hr-pre-baseline" },
            )
            assertTrue(trackingStore.heartRateSamples.values.any { it.sourceId == "hr-1" })
            assertTrue(trackingStore.heartRateSamples.values.any { it.sourceId == "hr-2" })
        }

    @Test
    fun `HR completes then HRV interrupted mid-stream - resumed HR re-read still prunes an HC-side deletion`() =
        runTest {
            // Task 4 review Finding 2 regression: HR always streams to completion before HRV starts
            // within a chunk attempt. When the crash happens mid-HRV, the checkpoint ends up with
            // hrPageToken = null (HR already complete, no token to store) and hrvPageToken = <token>
            // (HRV mid-stream). A single shared "resume this chunk" flag would incorrectly resume
            // HR's beginTypeScan too (because an HRV token is present), keeping attempt 1's stale HR
            // staged ids alive through attempt 2's fresh full HR re-read and hiding an HC-side
            // deletion from deletion reconciliation. Resume must be derived per type from that type's
            // own stored token instead.
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 2)
            val records = createCrossTypeResumeRecords()

            val staging = InMemoryScanStagingStore()
            val trackingStore = ResumptionTrackingStore(staging)
            val testUseCase = createResyncUseCase(trackingStore, staging, checkpointStore)
            mockCrossTypeResumePages(records)

            val run1Result =
                testUseCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertFalse(run1Result.isSuccess)
            assertNull(
                "HR completed in attempt 1, so no HR page token should be checkpointed",
                checkpointStore.value?.hrPageToken,
            )
            assertEquals("hrv-token-page-2", checkpointStore.value?.hrvPageToken)

            val run2Result =
                testUseCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)
            assertTrue(run2Result.isSuccess)

            assertFalse(
                "hr-1 was deleted upstream between attempts; it must not survive deletion reconciliation",
                trackingStore.heartRateSamples.values.any { it.sourceId == "hr-1" },
            )
            assertTrue(trackingStore.heartRateSamples.values.any { it.sourceId == "hr-2" })
        }

    private data class CrossTypeResumeRecords(
        val hrAttempt1: DomainHeartRateRecord,
        val hrAttempt2: DomainHeartRateRecord,
        val hrv1: DomainHrvRecord,
        val hrv2: DomainHrvRecord,
    )

    private fun createCrossTypeResumeRecords(): CrossTypeResumeRecords {
        val hrAttempt1 =
            DomainHeartRateRecord(
                id = "hr-1",
                deviceName = "Watch",
                samples = listOf(DomainHeartRateSample(Instant.parse("2024-06-01T10:00:00Z"), 65)),
                startTime = Instant.parse("2024-06-01T10:00:00Z"),
                endTime = Instant.parse("2024-06-01T10:01:00Z"),
            )
        // Attempt 2's fresh HR re-read (triggered because HR had already completed without a
        // stored token) observes that "hr-1" was deleted upstream between attempts -- only "hr-2"
        // remains.
        val hrAttempt2 =
            DomainHeartRateRecord(
                id = "hr-2",
                deviceName = "Watch",
                samples = listOf(DomainHeartRateSample(Instant.parse("2024-06-01T11:00:00Z"), 72)),
                startTime = Instant.parse("2024-06-01T11:00:00Z"),
                endTime = Instant.parse("2024-06-01T11:01:00Z"),
            )
        val hrv1 =
            DomainHrvRecord(
                id = "hrv-1",
                time = Instant.parse("2024-06-01T10:00:00Z"),
                rmssdMs = 40f,
                deviceName = "Watch",
            )
        val hrv2 =
            DomainHrvRecord(
                id = "hrv-2",
                time = Instant.parse("2024-06-01T11:00:00Z"),
                rmssdMs = 42f,
                deviceName = "Watch",
            )
        return CrossTypeResumeRecords(hrAttempt1, hrAttempt2, hrv1, hrv2)
    }

    private fun mockCrossTypeResumePages(records: CrossTypeResumeRecords) {
        var hrCallCount = 0
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
            hrCallCount++
            val onPage = invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
            val record = if (hrCallCount == 1) records.hrAttempt1 else records.hrAttempt2
            onPage(listOf(record), null)
            ReadOutcome.Available(Unit)
        }

        var hrvCallCount = 0
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } coAnswers {
            hrvCallCount++
            val token = invocation.args[2] as String?
            val onPage = invocation.args[3] as suspend (List<DomainHrvRecord>, String?) -> Unit
            if (hrvCallCount == 1) {
                onPage(listOf(records.hrv1), "hrv-token-page-2")
                error("Simulated worker kill after HRV page 1")
            } else {
                assertEquals("Resumed HRV pass must forward stored token", "hrv-token-page-2", token)
                onPage(listOf(records.hrv2), null)
                ReadOutcome.Available(Unit)
            }
        }
    }

    private fun createTestHeartRateInputs(): Pair<HeartRateInput, HeartRateInput> {
        val samplePreBaseline =
            HeartRateInput(
                id = "hr-pre-baseline",
                timestampMs = Instant.parse("2024-06-01T08:00:00Z").toEpochMilli(),
                beatsPerMinute = 60,
                recordType = "HEART_RATE",
                sessionId = null,
                deviceName = "Watch",
                sourceId = "hr-pre-baseline",
            )
        val sample1 =
            HeartRateInput(
                id = "hr-1",
                timestampMs = Instant.parse("2024-06-01T10:00:00Z").toEpochMilli(),
                beatsPerMinute = 65,
                recordType = "HEART_RATE",
                sessionId = null,
                deviceName = "Watch",
                sourceId = "hr-1",
            )
        return Pair(samplePreBaseline, sample1)
    }

    private fun createTestDomainRecords(): Pair<DomainHeartRateRecord, DomainHeartRateRecord> {
        val record1 =
            DomainHeartRateRecord(
                id = "hr-1",
                deviceName = "Watch",
                samples = listOf(DomainHeartRateSample(Instant.parse("2024-06-01T10:00:00Z"), 65)),
                startTime = Instant.parse("2024-06-01T10:00:00Z"),
                endTime = Instant.parse("2024-06-01T10:01:00Z"),
            )
        val record2 =
            DomainHeartRateRecord(
                id = "hr-2",
                deviceName = "Watch",
                samples = listOf(DomainHeartRateSample(Instant.parse("2024-06-01T11:00:00Z"), 72)),
                startTime = Instant.parse("2024-06-01T11:00:00Z"),
                endTime = Instant.parse("2024-06-01T11:01:00Z"),
            )
        return Pair(record1, record2)
    }

    private fun mockResumedHeartRatePages(
        record1: DomainHeartRateRecord,
        record2: DomainHeartRateRecord,
    ) {
        var callCount = 0
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
            callCount++
            val token = invocation.args[2] as String?
            val onPage = invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
            if (callCount == 1) {
                onPage(listOf(record1), "token-page-2")
                error("Simulated worker kill after page 1")
            } else {
                assertEquals("Resumed pass must forward stored token", "token-page-2", token)
                onPage(listOf(record2), null)
                ReadOutcome.Available(Unit)
            }
        }
    }

    private fun createResyncUseCase(
        store: ResumptionTrackingStore,
        staging: InMemoryScanStagingStore,
        checkpoint: ResyncCheckpointStore,
    ): ResyncRangeUseCase =
        ResyncRangeUseCase(
            settingsRepo = settingsRepo,
            clock = Clock.fixed(Instant.parse("2026-08-31T12:00:00Z"), ZoneId.of("UTC")),
            sessionLinkReconciler = sessionLinkReconciler,
            changeSynchronizer = changeSynchronizer,
            selectedSourcePruner = selectedSourcePruner,
            checkpointStore = checkpoint,
            healthIngestionStore = store,
            ingestion =
                ResyncIngestionDependencies(
                    ingestionCoordinator = HealthIngestionCoordinator(hcRepo, store, staging = staging),
                    stepCountFetcher = StepCountFetcher(hcRepo),
                    staging = staging,
                ),
            recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
            ioDispatcher = Dispatchers.Unconfined,
        )

    private suspend fun runUninterruptedRun(
        startDate: LocalDate,
        endDate: LocalDate,
        records: List<DomainHeartRateRecord>,
        initialSamples: List<HeartRateInput>,
    ): Set<String?> {
        val staging = InMemoryScanStagingStore()
        val store = ResumptionTrackingStore(staging)
        initialSamples.forEach { store.heartRateSamples[it.id] = it }
        val useCase = createResyncUseCase(store, staging, InMemoryResyncCheckpointStore())
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
            val onPage = invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
            onPage(listOf(records[0]), "token-page-2")
            onPage(listOf(records[1]), null)
            ReadOutcome.Available(Unit)
        }
        val result = useCase.run(
            startDate = startDate,
            endDate = endDate,
            chunkDays = 30,
            onProgress = null,
        )
        assertTrue(result.isSuccess)
        return store.heartRateSamples.values.map { it.sourceId }.toSet()
    }

    private fun createRunIdentity(
        startDate: LocalDate,
        endDate: LocalDate,
        mode: String = HistoricalRunIdentity.MODE_FULL_INGEST,
        prefs: UserPreferences = UserPreferences(),
    ): HistoricalRunIdentity {
        val resolvedHrMax =
            if (prefs.autoCalculateMaxHr) {
                (208 - 0.7 * prefs.age).toFloat()
            } else {
                prefs.maxHeartRate.toFloat()
            }
        return HistoricalRunIdentity.create(
            runId = "test-run",
            mode = mode,
            startDate = startDate,
            endDate = endDate,
            zoneId = prefs.scoringZone(),
            prefs = prefs,
            resolvedHrMax = resolvedHrMax,
            startedAtEpochMs = 1_000_000L,
        )
    }

    private class ResumptionTrackingStore(
        private val staging: InMemoryScanStagingStore,
    ) : HealthIngestionStore {
        val heartRateSamples = mutableMapOf<String, HeartRateInput>()

        override suspend fun persist(batch: HealthIngestionBatch) = Unit

        override suspend fun replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>) {
            sources.forEach { source ->
                source.rows.forEach { heartRateSamples[it.id] = it }
            }
        }

        override suspend fun replaceHrvSources(sources: List<SourcePayload<HrvInput>>) = Unit
        override suspend fun clearFrozenBaselines(start: LocalDate, endExclusive: LocalDate, zoneId: ZoneId) = Unit
        override suspend fun countHeartRateInRange(startMs: Long, endMs: Long): Int = heartRateSamples.size
        override suspend fun countHrvInRange(startMs: Long, endMs: Long): Int = 0
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
        ): ScoreInvalidation.AffectedRange? {
            if (scan.type == HealthDataType.HEART_RATE) {
                val scannedIds = staging.stagedIds(scan.scan, scan.type)
                val toDelete = heartRateSamples.entries.filter { it.value.sourceId !in scannedIds }
                toDelete.forEach { heartRateSamples.remove(it.key) }
            }
            return null
        }
    }
}
