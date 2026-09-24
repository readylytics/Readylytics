package app.readylytics.health.core.healthconnect.domain.sync
import app.readylytics.health.core.model.domain.sync.*
import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.model.domain.model.DomainHeartRateRecord
import app.readylytics.health.core.model.domain.model.DomainHrvRecord
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import app.readylytics.health.core.model.domain.repository.HealthConnectWindowTimeoutException
import app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException
import app.readylytics.health.core.model.domain.repository.ReadOutcome
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.repository.WalDiagnostics
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.repository.WalkForwardTrimpContext
import app.readylytics.health.core.scoring.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.core.model.domain.sync.link.SessionLinkReconciler
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertFailsWith
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts

class DailySyncUseCaseProgressTest : DailySyncUseCaseTestFixture() {
    @Test
    fun `daily sync absorbs change exactly at the inline floor inline`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            // Exactly MAX_INLINE_RECOMPUTE_DAYS (7) back: the floor is inclusive, so still inline.
            val floorDay = today.minusDays(7)
            val nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token")
            val scoredDays = mutableListOf<LocalDate>()

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(floorDay),
                    requiresFullResync = false,
                    nextTokens = nextTokens,
                )
            coJustRun {
                scoringRepository.computeAndPersistDailySummary(
                    capture(scoredDays),
                    any(),
                    any(),
                    any(),
                    any())
            }

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertEquals(floorDay, scoredDays.first())
            assertEquals(today, scoredDays.last())
            assertTrue(result is app.readylytics.health.core.model.domain.model.Result.Success)
            coVerify(exactly = 1) { changeSynchronizer.commitTokens(nextTokens) }
        }

    @Test
    fun `sync rethrows cancellation instead of converting to failure`() =
        runTest {
            coEvery { hcRepo.readSleepSessions(any(), any()) } throws CancellationException("cancelled")

            assertFailsWith<CancellationException> {
                useCase.run(windowDays = 1, onProgress = null)
            }
        }

    @Test
    fun `sync rethrows permission-revoked instead of flattening to SYNC_ERROR`() =
        runTest {
            // HC-008: a revoked Health Connect permission must surface distinctly so
            // ForegroundSyncController/the periodic worker can route to the permission-recovery
            // flow, not be swallowed into a generic Result.Failure("SYNC_ERROR").
            coEvery { hcRepo.readSleepSessions(any(), any()) } throws
                app.readylytics.health.core.model.domain.repository.HealthConnectPermissionRevokedException(
                    SecurityException("revoked"),
                )

            assertFailsWith<HealthConnectPermissionRevokedException> {
                useCase.run(windowDays = 1, onProgress = null)
            }
        }

    @Test
    fun `sync resolves today from the injected clock, not the real system clock`() =
        runTest {
            // DI-002: a use case wired to a clock fixed on a historical date must resolve "today"
            // from that clock, never from the machine's real wall-clock date. A historical instant
            // (2019, long past) makes the assertion exact and immune to coincidental matches with
            // whatever day this test actually runs on.
            val historicalClock = Clock.fixed(Instant.parse("2019-01-10T08:00:00Z"), ZoneId.of("UTC"))
            val clockedUseCase =
                DailySyncUseCase(
                    settingsRepo = settingsRepo,
                    rasSourceModeBootstrapUseCase = rasSourceModeBootstrapUseCase,
                    recomputeSupport = DailyRecomputeSupport(scoringRepository, settingsRepo, transactionRunner),
                    dirtyRangeStore = FakeDirtyRangeStore(),
                    walDiagnostics = walDiagnostics,
                    ingestion =
                        DailySyncIngestionCollaborators(
                            sessionLinkReconciler = sessionLinkReconciler,
                            changeSynchronizer = changeSynchronizer,
                            healthIngestionStore = healthIngestionStore,
                            ingestionCoordinator =
                                HealthIngestionCoordinator(hcRepo, healthIngestionStore, FakeScanStagingStore()),
                            stepCountFetcher = StepCountFetcher(hcRepo),
                        ),
                    ioDispatcher = Dispatchers.Unconfined,
                    clock = historicalClock,
                )
            every { settingsRepo.userPreferences } returns flowOf(UserPreferences(scoringZoneId = "UTC"))
            val expectedDay = LocalDate.of(2019, 1, 10)

            clockedUseCase.run(windowDays = 1, onProgress = null)

            coVerify {
                scoringRepository.computeAndPersistDailySummary(expectedDay, any(), any(), any(), any())
            }
            coVerify(exactly = 0) {
                scoringRepository.computeAndPersistDailySummary(
                    LocalDate.now(ZoneId.of("UTC")),
                    any(),
                    any(),
                    any(),
                    any())
            }
        }

    @Test
    fun `sync executes baseline clear and each day in its own transaction`() =
        runTest {
            // R2-CACHE-002: Baseline clear runs in its own transaction (tx #1), then each day
            // recomputes in its own transaction (tx #2..tx #9 for windowDays = 8).
            useCase.run(windowDays = 8, onProgress = null)

            assertEquals(9, transactionRunner.transactionCount)
            assertEquals(1, transactionRunner.maxDepth)
        }

    @Test
    fun `sync clears frozen baselines and scores each day inside its own transaction`() =
        runTest {
            val transactions = mutableListOf<String>()
            coEvery { healthIngestionStore.clearFrozenBaselines(any(), any(), any()) } answers {
                transactions += "clear:tx#${transactionRunner.transactionCount}:depth${transactionRunner.openDepth}"
            }
            coEvery {
                scoringRepository.computeAndPersistDailySummary(any(), any(), any(), any(), any())
            } answers {
                transactions += "score:tx#${transactionRunner.transactionCount}:depth${transactionRunner.openDepth}"
            }

            useCase.run(windowDays = 3, onProgress = null)

            assertEquals(
                listOf(
                    "clear:tx#1:depth1",
                    "score:tx#2:depth1",
                    "score:tx#3:depth1",
                    "score:tx#4:depth1",
                ),
                transactions,
            )
            assertEquals(4, transactionRunner.transactionCount)
            assertEquals(1, transactionRunner.maxDepth)
        }

    @Test
    fun `recompute cancellation mid-sync retains already completed day transactions`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val day0 = today.minusDays(2)
            val day1 = today.minusDays(1)

            coEvery {
                scoringRepository.computeAndPersistDailySummary(day0, any(), any(), any(), any())
            } returns Unit
            coEvery {
                scoringRepository.computeAndPersistDailySummary(day1, any(), any(), any(), any())
            } throws CancellationException("sync cancelled mid-walkforward")

            assertFailsWith<CancellationException> {
                useCase.run(windowDays = 3, onProgress = null)
            }

            // Baseline clear was tx #1, day 0 was tx #2, day 1 failed in tx #3
            assertEquals(3, transactionRunner.transactionCount)
        }

    @Test
    fun `sync emits an indeterminate RECONCILE progress signal before reconcile runs`() =
        runTest {
            // US-003: onProgress must fire (RECONCILE, 0, 0) before sessionLinkReconciler.reconcile
            // is invoked, so the UI banner switches to the RECONCILE label before that phase starts.
            val events = mutableListOf<String>()
            val onProgress: (ResyncPhase, Int, Int) -> Unit = { phase, current, total ->
                if (phase == ResyncPhase.RECONCILE) events += "progress:RECONCILE:$current:$total"
            }
            coEvery { sessionLinkReconciler.reconcile(any(), any(), any()) } answers {
                events += "reconcile:called"
            }

            useCase.run(windowDays = 1, onProgress = onProgress)

            assertEquals(listOf("progress:RECONCILE:0:0", "reconcile:called"), events)
        }

    @Test
    fun `sync emits incrementing indeterminate INGEST progress signals per streamed page`() =
        runTest {
            // US-004: each HR/HRV page persisted during ingestWindow must report an indeterminate
            // (total = 0) INGEST signal with a monotonically incrementing page count. M4: the first
            // page carries 2 records and the second carries 1 -- if the counter incremented per
            // record instead of per page, this would report (…, 2, 0) then (…, 3, 0) instead of the
            // expected (…, 1, 0) then (…, 2, 0), so this distinguishes the two implementations.
            val progressEvents = mutableListOf<Triple<ResyncPhase, Int, Int>>()
            val onProgress: (ResyncPhase, Int, Int) -> Unit = { phase, current, total ->
                progressEvents += Triple(phase, current, total)
            }
            coEvery { hcRepo.readHeartRateSamplesPaged(any(), any(), any(), any()) } coAnswers {
                val callback = it.invocation.args[3] as suspend (List<DomainHeartRateRecord>, String?) -> Unit
                callback(listOf(mockk(relaxed = true), mockk(relaxed = true)), "page-2")
                callback(listOf(mockk(relaxed = true)), null)
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(Unit)
            }

            useCase.run(windowDays = 1, onProgress = onProgress)

            val ingestEvents = progressEvents.filter { it.first == ResyncPhase.INGEST }
            // B′: the page counter is local to each ingestWindow call, so it resets to 1 when the
            // back-day segment starts. Both segments emit (1,0) then (2,0).
            assertEquals(
                listOf(
                    Triple(ResyncPhase.INGEST, 1, 0),
                    Triple(ResyncPhase.INGEST, 2, 0),
                    Triple(ResyncPhase.INGEST, 1, 0),
                    Triple(ResyncPhase.INGEST, 2, 0),
                ),
                ingestEvents,
            )
        }

    @Test
    fun `sync opens no transaction around the Health Connect window read`() =
        runTest {
            // Holding a write transaction across HC IPC would pin the transaction thread for the
            // duration of a remote read. Ingestion, reconcile and the step fetch must all be done
            // before the transaction opens.
            var depthDuringHcRead = -1
            coEvery { hcRepo.readSteps(any(), any()) } answers {
                depthDuringHcRead = transactionRunner.openDepth
                app.readylytics.health.core.model.domain.repository.ReadOutcome.Available(0L)
            }

            useCase.run(windowDays = 2, onProgress = null)

            assertEquals(0, depthDuringHcRead)
        }

    @Test
    fun `a sync that requires a historical resync does not advance lastSyncTimestamp`() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(fixedClock.withZone(zoneId))
            val oldestAffectedDay = today.minusDays(8)

            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = setOf(oldestAffectedDay),
                    requiresFullResync = false,
                    nextTokens = mapOf(HealthDataType.SLEEP to "next-sleep-token"),
                )

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertEquals(
                "REQUIRES_HISTORICAL_RESYNC",
                (result as app.readylytics.health.core.model.domain.model.Result.Failure).code,
            )
            coVerify(exactly = 0) { settingsRepo.updateLastSyncTimestamp(any()) }
        }

    @Test
    fun `a sync with change synchronizer requesting full resync does not advance lastSyncTimestamp`() =
        runTest {
            coEvery { changeSynchronizer.applyPendingChanges() } returns
                HealthChangeSyncOutcome(
                    affectedDates = emptySet(),
                    requiresFullResync = true,
                )

            val result = useCase.run(windowDays = 1, onProgress = null)

            assertEquals(
                "REQUIRES_HISTORICAL_RESYNC",
                (result as app.readylytics.health.core.model.domain.model.Result.Failure).code,
            )
            coVerify(exactly = 0) { settingsRepo.updateLastSyncTimestamp(any()) }
        }

    @Test
    fun `sync updates lastSyncTimestamp on success`() =
        runTest {
            val result = useCase.run(windowDays = 1, onProgress = null)

            assertTrue(result is app.readylytics.health.core.model.domain.model.Result.Success)
            coVerify(exactly = 1) { settingsRepo.updateLastSyncTimestamp(fixedClock.millis()) }
        }
}
