package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

class ResyncCheckpointResumeTest {
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
        // PERF-002/WP-20/WP-22: every non-empty RECOMPUTE range now fetches batched TRIMP-series and
        // baseline contexts once up front via these methods before calling the 6-arg
        // computeAndPersistDailySummary overload.
        coEvery { scoringRepository.fetchWalkForwardTrimpContext(any(), any(), any()) } returns
            WalkForwardTrimpContext(TreeMap(), TreeMap())
        coEvery { scoringRepository.fetchWalkForwardBaselineContext(any(), any(), any()) } returns
            WalkForwardBaselineContext(emptyList())
        // WP-27: the walk-forward also prefetches one mutable residual-fatigue accumulator per run.
        // A relaxed mock would return null here, and the recompute loop's non-null-context guard
        // would then silently fall back to the 3-arg recomputeDay (no fatigue computed) -- stub it
        // so the walk-forward actually exercises the 6-arg path.
        coEvery { scoringRepository.fetchWalkForwardFatigueContext(any(), any(), any()) } returns
            WalkForwardFatigueContext(emptyList())
        coEvery { hcRepo.readSleepSessions(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readExerciseSessions(any(), any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)
        coEvery { hcRepo.readStepsRecords(any(), any()) } returns ReadOutcome.Available(emptyList())
        coEvery { hcRepo.readSteps(any(), any()) } returns ReadOutcome.Available(0L)
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
            zoneId = ZoneId.systemDefault(),
            prefs = prefs,
            resolvedHrMax = resolvedHrMax,
            startedAtEpochMs = 1000L,
        )
    }

    @Test
    fun `resyncRange resumes ingest from saved chunk checkpoint`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 3)
            val resumedChunkStart = LocalDate.of(2024, 7, 1)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = resumedChunkStart,
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                    runIdentity = createRunIdentity(startDate, endDate),
                )

            val sleepFromSlot = slot<Instant>()
            coEvery {
                hcRepo.readSleepSessions(capture(sleepFromSlot), any())
            } returns ReadOutcome.Available(emptyList())

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals(
                resumedChunkStart.minusDays(1).atStartOfDay(zoneId).toInstant(),
                sleepFromSlot.captured,
            )
        }

    @Test
    fun `resyncRange resumes recompute from saved day and reports completed progress`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 4)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.RECOMPUTE,
                    nextDate = startDate.plusDays(2),
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                    runIdentity = createRunIdentity(startDate, endDate),
                )
            val progress = mutableListOf<Triple<ResyncPhase, Int, Int>>()

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = { phase, current, total ->
                    progress += Triple(phase, current, total)
                },
            )

            assertEquals(Triple(ResyncPhase.RECOMPUTE, 2, 4), progress.first())
            coVerify(
                exactly = 0,
            ) { scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any()) }
            coVerify(
                exactly = 0,
            ) {
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(1),
                    any(),
                    any(),
                    any())
            }
            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(2),
                    any(),
                    any(),
                    any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any(), any())
            }
        }

    @Test
    fun `recompute resume after a committed 30-day chunk rebuilds fatigue context at the checkpoint boundary`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val resumedStart = startDate.plusDays(30)
            val endDate = resumedStart.plusDays(1)
            val resumedFatigueContext = WalkForwardFatigueContext(emptyList())
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.RECOMPUTE,
                    nextDate = resumedStart,
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                    runIdentity = createRunIdentity(startDate, endDate),
                )
            coEvery {
                scoringRepository.fetchWalkForwardFatigueContext(resumedStart, endDate, any())
            } returns resumedFatigueContext

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            coVerify(exactly = 1) {
                scoringRepository.fetchWalkForwardFatigueContext(resumedStart, endDate, any())
            }
            coVerify(exactly = 2) {
                scoringRepository.computeAndPersistDailySummary(
                    any(),
                    any(),
                    any(),
                    match { it.fatigue === resumedFatigueContext },
                )
            }
        }

    @Test
    fun `resyncRange discards mismatched checkpoint and restarts from requested range`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 3)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate.minusDays(10),
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = LocalDate.of(2024, 7, 1),
                    selectionHash = "stale",
                    baselineChangeTokens = baselineTokens,
                    runIdentity = createRunIdentity(startDate.minusDays(10), endDate),
                )

            val sleepFromInstants = mutableListOf<Instant>()
            coEvery {
                hcRepo.readSleepSessions(capture(sleepFromInstants), any())
            } returns ReadOutcome.Available(emptyList())

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals(
                startDate.minusDays(1).atStartOfDay(zoneId).toInstant(),
                sleepFromInstants.first(),
            )
        }

    @Test
    fun `full resync rejects matching checkpoint without baseline tokens and restarts ingest`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 7, 3)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = LocalDate.of(2024, 7, 1),
                    selectionHash = "",
                    baselineChangeTokens = emptyMap(),
                    runIdentity = createRunIdentity(startDate, endDate),
                )

            val sleepFromSlot = slot<Instant>()
            coEvery { hcRepo.readSleepSessions(capture(sleepFromSlot), any()) } throws
                IllegalStateException("stop after checkpoint initialization")

            val result =
                useCase.run(startDate = startDate, endDate = endDate, chunkDays = 30, onProgress = null)

            assertEquals(false, result.isSuccess)
            assertEquals(
                startDate.minusDays(1).atStartOfDay(zoneId).toInstant(),
                sleepFromSlot.captured,
            )
            coVerify(exactly = 1) { changeSynchronizer.captureChangesTokens() }
            assertEquals(ResyncPhase.INGEST, checkpointStore.value?.phase)
            assertEquals(startDate, checkpointStore.value?.nextDate)
            assertEquals(baselineTokens, checkpointStore.value?.baselineChangeTokens)
        }

    @Test
    fun `resyncRange captures baseline tokens before ingest and promotes them after recompute`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)

            useCase.run(startDate = startDate, endDate = startDate, chunkDays = 30, onProgress = null)

            coVerifyOrder {
                changeSynchronizer.captureChangesTokens()
                hcRepo.readSleepSessions(any(), any())
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any())
                changeSynchronizer.commitTokens(baselineTokens)
            }
            assertEquals(null, checkpointStore.value)
        }

    @Test
    fun `resyncRange promotes only completed types and does not promote denied types`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val multiTokens =
                mapOf(
                    HealthDataType.SLEEP to "baseline-sleep-token",
                    HealthDataType.HRV to "baseline-hrv-token",
                )
            coEvery { changeSynchronizer.captureChangesTokens() } returns multiTokens
            coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Denied

            useCase.run(startDate = startDate, endDate = startDate, chunkDays = 30, onProgress = null)

            coVerify {
                changeSynchronizer.commitTokens(
                    match { tokens ->
                        tokens.containsKey(HealthDataType.SLEEP) && !tokens.containsKey(HealthDataType.HRV)
                    },
                )
            }
        }

    @Test
    fun `denied type excluded by an earlier committed chunk is not re-promoted after a later chunk succeeds for it`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 4)
            val resumedChunkStart = LocalDate.of(2024, 6, 3)
            val hrvOnlyTokens = mapOf(HealthDataType.HRV to "baseline-hrv-token")

            // Simulate a checkpoint saved right after chunk 1 (6/1-6/2) committed: HRV was denied
            // during that chunk, so the chunk-completion intersect narrowed completedTypes down to
            // genuinely empty. completedTypesRecorded=true because this checkpoint was written by
            // completedTypes-aware (post-H1) code, not decoded from a legacy/absent-field proto.
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = resumedChunkStart,
                    selectionHash = "",
                    baselineChangeTokens = hrvOnlyTokens,
                    completedTypes = emptySet(),
                    completedTypesRecorded = true,
                    runIdentity = createRunIdentity(startDate, endDate),
                )

            // The resumed chunk (6/3-6/4) regrants HRV -- it must NOT resurrect HRV's promotion
            // eligibility: chunk 1's denial was already committed and is never reprocessed on
            // resume, so it must permanently exclude HRV for the rest of this run.
            coEvery { hcRepo.readHrvSamplesPaged(any(), any(), any(), any()) } returns ReadOutcome.Available(Unit)

            useCase.run(startDate = startDate, endDate = endDate, chunkDays = 2, onProgress = null)

            coVerify(exactly = 0) {
                changeSynchronizer.commitTokens(match { tokens -> tokens.containsKey(HealthDataType.HRV) })
            }
        }

    @Test
    fun `checkpoint without completedTypesRecorded falls back to the permissive baseline candidate set`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)

            // A checkpoint decoded from a proto that predates completedTypesRecorded (or predates
            // completedTypes itself) always decodes that flag to false -- it must be treated as
            // "completedTypes absent", replaying conservatively via the pre-H1 permissive default,
            // never as "genuinely narrowed to empty".
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = startDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = startDate,
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                    completedTypes = emptySet(),
                    completedTypesRecorded = false,
                    runIdentity = createRunIdentity(startDate, startDate),
                )

            useCase.run(startDate = startDate, endDate = startDate, chunkDays = 30, onProgress = null)

            coVerify {
                changeSynchronizer.commitTokens(match { tokens -> tokens.containsKey(HealthDataType.SLEEP) })
            }
        }

    @Test
    fun `interrupted HR page token in checkpoint is cleared and replayed from beginning on resume`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = startDate,
                    phase = ResyncPhase.INGEST,
                    nextDate = startDate,
                    selectionHash = "",
                    baselineChangeTokens = baselineTokens,
                    hrPageToken = "saved-token-2",
                    runIdentity = createRunIdentity(startDate, startDate),
                )

            val tokenSlot = slot<String?>()
            coEvery {
                hcRepo.readHeartRateSamplesPaged(any(), any(), captureNullable(tokenSlot), any())
            } returns ReadOutcome.Available(Unit)

            useCase.run(startDate = startDate, endDate = startDate, chunkDays = 30, onProgress = null)

            assertEquals(null, tokenSlot.captured)
        }

    @Test
    fun `resyncRange keeps checkpoint and tokens when recompute fails`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            coEvery {
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any())
            } throws
                IllegalStateException("scoring failed")

            val result = useCase.run(startDate = startDate, endDate = startDate, chunkDays = 30, onProgress = null)

            assertEquals(false, result.isSuccess)
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
            assertEquals(ResyncPhase.RECOMPUTE, checkpointStore.value?.phase)
            assertEquals(startDate, checkpointStore.value?.nextDate)
        }

    @Test
    fun `recompute resumes with the same scoring preferences without Health Connect tokens`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            val preferences = MutableStateFlow(UserPreferences(trimpModel = TrimpModel.BANISTER))
            every { settingsRepo.userPreferences } returns preferences
            coEvery {
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(1),
                    any(),
                    any(),
                    any())
            } throws IllegalStateException("scoring failed")

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            // PERF-002/WP-20: checkpoints during RECOMPUTE now save every
            // RECOMPUTE_CHECKPOINT_INTERVAL_DAYS days (or on the final day) instead of every day, so
            // day 1 (startDate) succeeding doesn't advance the checkpoint past the pre-loop
            // RECONCILE->RECOMPUTE save at nextDate=startDate -- only the day that actually threw
            // matters for whether a save happened, and it didn't get the chance to. Recompute is
            // idempotent, so the resumed run below correctly redoes startDate too.
            val failedCheckpoint = requireNotNull(checkpointStore.value)
            assertEquals(ResyncPhase.RECOMPUTE, failedCheckpoint.phase)
            assertEquals(startDate, failedCheckpoint.nextDate)
            assertEquals(emptyMap<HealthDataType, String>(), failedCheckpoint.baselineChangeTokens)

            clearMocks(scoringRepository, answers = false, recordedCalls = true)
            coEvery {
                scoringRepository.computeAndPersistDailySummary(any(), any(), any(), any())
            } returns Unit

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(1),
                    any(),
                    any(),
                    any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any(), any())
            }
            coVerify(exactly = 0) { changeSynchronizer.captureChangesTokens() }
            coVerify(exactly = 0) { changeSynchronizer.applyPendingChanges() }
            coVerify(exactly = 0) { changeSynchronizer.commitTokens(any()) }
        }

    @Test
    fun `recompute restarts from start when a scoring preference changes`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            val preferences = MutableStateFlow(UserPreferences(trimpModel = TrimpModel.BANISTER))
            every { settingsRepo.userPreferences } returns preferences
            coEvery {
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(1),
                    any(),
                    any(),
                    any())
            } throws IllegalStateException("scoring failed")

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            // PERF-002/WP-20: see the equivalent comment in the "same scoring preferences" test above
            // -- day 1 succeeding doesn't move the checkpoint past the pre-loop
            // RECONCILE->RECOMPUTE save under the new N-day checkpoint granularity.
            val failedCheckpoint = requireNotNull(checkpointStore.value)
            assertEquals(ResyncPhase.RECOMPUTE, failedCheckpoint.phase)
            assertEquals(startDate, failedCheckpoint.nextDate)
            assertEquals(emptyMap<HealthDataType, String>(), failedCheckpoint.baselineChangeTokens)

            clearMocks(scoringRepository, answers = false, recordedCalls = true)
            preferences.value = UserPreferences(trimpModel = TrimpModel.CHENG)
            coEvery {
                scoringRepository.computeAndPersistDailySummary(any(), any(), any(), any())
            } returns Unit

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(1),
                    any(),
                    any(),
                    any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any(), any())
            }
            assertEquals(null, checkpointStore.value)
        }

    @Test
    fun `recompute restarts from start when residualFatigueHalfLifeHours changes`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            val oldPrefs = UserPreferences(residualFatigueHalfLifeHours = 36f)
            val newPrefs = UserPreferences(residualFatigueHalfLifeHours = 48f)
            val preferences = MutableStateFlow(newPrefs)
            every { settingsRepo.userPreferences } returns preferences
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.RECOMPUTE,
                    nextDate = startDate.plusDays(2),
                    selectionHash = "RECOMPUTE_ONLY_V2||${oldPrefs.scoringCheckpointIdentity()}",
                    baselineChangeTokens = emptyMap(),
                    runIdentity =
                        createRunIdentity(
                            startDate,
                            endDate,
                            mode = HistoricalRunIdentity.MODE_RECOMPUTE_ONLY,
                            prefs = oldPrefs,
                        ),
                )

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(1),
                    any(),
                    any(),
                    any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any(), any())
            }
            assertEquals(null, checkpointStore.value)
        }

    @Test
    fun `recompute restarts from start when residualFatigueGain changes`() =
        runTest {
            val startDate = LocalDate.of(2024, 6, 1)
            val endDate = LocalDate.of(2024, 6, 3)
            val oldPrefs = UserPreferences(residualFatigueGain = 1.0f)
            val newPrefs = UserPreferences(residualFatigueGain = 2.5f)
            val preferences = MutableStateFlow(newPrefs)
            every { settingsRepo.userPreferences } returns preferences
            checkpointStore.value =
                ResyncCheckpoint(
                    startDate = startDate,
                    endDate = endDate,
                    phase = ResyncPhase.RECOMPUTE,
                    nextDate = startDate.plusDays(2),
                    selectionHash = "RECOMPUTE_ONLY_V2||${oldPrefs.scoringCheckpointIdentity()}",
                    baselineChangeTokens = emptyMap(),
                    runIdentity =
                        createRunIdentity(
                            startDate,
                            endDate,
                            mode = HistoricalRunIdentity.MODE_RECOMPUTE_ONLY,
                            prefs = oldPrefs,
                        ),
                )

            useCase.run(
                startDate = startDate,
                endDate = endDate,
                chunkDays = 30,
                onProgress = null,
                skipIngestAndPrune = true,
            )

            coVerifyOrder {
                scoringRepository.computeAndPersistDailySummary(startDate, any(), any(), any())
                scoringRepository.computeAndPersistDailySummary(
                    startDate.plusDays(1),
                    any(),
                    any(),
                    any())
                scoringRepository.computeAndPersistDailySummary(endDate, any(), any(), any())
            }
            assertEquals(null, checkpointStore.value)
        }
}

class InMemoryResyncCheckpointStore : ResyncCheckpointStore {
    private val state = MutableStateFlow<ResyncCheckpoint?>(null)

    var onSave: (() -> Unit)? = null

    var value: ResyncCheckpoint?
        get() = state.value
        set(value) {
            state.value = value
        }

    override val checkpoint: Flow<ResyncCheckpoint?> = state

    override suspend fun save(checkpoint: ResyncCheckpoint) {
        onSave?.invoke()
        state.value = checkpoint
    }

    override suspend fun clear() {
        state.value = null
    }
}
