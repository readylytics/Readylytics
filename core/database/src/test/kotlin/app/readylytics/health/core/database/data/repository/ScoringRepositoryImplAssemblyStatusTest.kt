package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase

import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationDecision
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationSnapshot
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationState
import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.repository.WalkForwardFatigueContext
import app.readylytics.health.core.model.domain.scoring.DayAssemblyUnavailableReason
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C3 (WP-13): behavior tests for `DayAssembly`/`freshDaySummary` as they thread through
 * [ScoringRepositoryImpl] -- kept in a dedicated file (rather than folded into
 * [ScoringRepositoryImplTest]) purely to stay under detekt's `LargeClass` limit; the fixture
 * boilerplate below intentionally mirrors that file's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScoringRepositoryImplAssemblyStatusTest {
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val baselineComputer = mockk<BaselineComputer>(relaxed = true)
    private val computeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)
    private val scoringConfigFactory = mockk<ScoringConfigFactory>(relaxed = true)
    private val computeWorkoutTrimpUseCase = mockk<ComputeWorkoutTrimpUseCase>(relaxed = true)
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)
    private val vo2MaxRecordDao = mockk<Vo2MaxRecordDao>(relaxed = true)
    private val scoringHistoryRepository = mockk<ScoringHistoryRepository>(relaxed = true)
    private val dataLoader = ScoringDayDataLoader(workoutDao, sleepSessionDao, dailySummaryDao)
    private val bodyMetricsDataLoader =
        BodyMetricsDataLoader(
            weightRecordDao,
            bodyFatRecordDao,
            bloodPressureRecordDao,
            oxygenSaturationRecordDao,
            bodyTemperatureRecordDao,
            vo2MaxRecordDao,
        )
    private val seriesLoader = ScoringSeriesLoader(workoutDao, dailySummaryDao)
    private val heartRateDataLoader = ScoringHeartRateDataLoader(heartRateDao, minuteBucketDao)

    private lateinit var repo: ScoringRepositoryImpl

    private fun createRepo(
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        sleepSessionRepository: SleepSessionRepository = mockk(relaxed = true),
    ): ScoringRepositoryImpl {
        val readinessSummaryCoordinator =
            ReadinessSummaryCoordinator(
                dataLoader,
                seriesLoader,
                scoringHistoryRepository,
                baselineComputer,
                BuildLoadSeriesUseCase(scoringCalculator),
                computeSleepMetricsUseCase,
                ResolveDailyBaselinesUseCase(baselineComputer),
                AssembleDailySummaryUseCase(),
            )
        return ScoringRepositoryImpl(
            ScoringDataLoaders(
                dataLoader,
                bodyMetricsDataLoader,
                seriesLoader,
                heartRateDataLoader,
            ),
            settingsRepo,
            baselineComputer,
            scoringConfigFactory,
            ScoringDayUseCases(
                ComputeDailyTrimpUseCase(computeWorkoutTrimpUseCase),
                ComputeResidualFatigueUseCase(),
                ResolveDailyBaselinesUseCase(baselineComputer),
                AssembleEverydayLoadInputUseCase(),
                ComputeTrainingReadinessUseCase(scoringCalculator),
                UthVo2MaxCalculator(),
                Vo2MaxSourceResolver(),
            ),
            scoringHistoryRepository,
            readinessSummaryCoordinator,
            dispatcher,
            MorningRecommendationDependencies(
                sleepSessionRepository = sleepSessionRepository,
                computeSleepMetricsUseCase = mockk(relaxed = true),
                hrvResolver = mockk(relaxed = true),
                workoutRepository = mockk(relaxed = true),
                dailySummaryRepository = mockk(relaxed = true),
                getWorkoutDisplayMetricsUseCase = mockk(relaxed = true),
            ),
        )
    }

    @Before
    fun setup() {
        repo = createRepo()
        every { settingsRepo.userPreferences } returns flowOf(UserPreferences())
        coEvery { dailySummaryDao.getByDate(any()) } returns null
        coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), any()) } returns null
        coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns emptyList()
        coEvery { sleepSessionDao.countSince(any()) } returns 10
        coEvery { scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any()) } returns 6
        coEvery {
            baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any(), any(), any(), null)
        } returns 60f
        coEvery {
            baselineComputer.computeAdaptiveBaselineRhrBpmBetween(any(), any(), any(), any(), any(), any())
        } returns 60f
        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any(), any(), null) } returns
            BaselineComputer.HrvWindows(
                muHistory = emptyList(),
                sigmaHistory = emptyList(),
                historicalSessions = emptyList(),
                validHistoricalSessionIds = emptyList(),
                validHistoricalDayCount = 6,
            )
        coEvery { baselineComputer.computeHrvWindowsBetween(any(), any(), any(), any(), any(), any()) } returns
            BaselineComputer.HrvWindows(
                muHistory = emptyList(),
                sigmaHistory = emptyList(),
                historicalSessions = emptyList(),
                validHistoricalSessionIds = emptyList(),
                validHistoricalDayCount = 6,
            )
    }

    private fun previousWithStaleSleepPayload(day: LocalDate): DailySummary =
        DailySummary(
            date = day,
            sleepScore = 90f,
            nocturnalHrv = 55,
            sleepDurationMinutes = 480,
            sRest = 88f,
            baselineCalculatedAtDate = day.minusDays(1),
            hrMax = 188f,
            readinessResult =
                ReadinessResult.EMPTY.copy(
                    recoveryFlags = setOf(RecoveryFlag.STRONG_RECOVERY_SIGNAL),
                ),
            workoutRecommendation =
                WorkoutRecommendationSnapshot(
                    ruleVersion = 1,
                    wakeSessionId = "old-session",
                    wakeTimeMs = 1_000L,
                    decision = WorkoutRecommendationDecision(WorkoutRecommendationState.EASY, emptyList()),
                ),
        )

    // C3 (WP-13): deleting a day's only sleep session must not let its stale derived fields
    // (sleep score, restoration, readiness flags, recommendation) survive a recompute -- see
    // freshDaySummary/DayAssembly.Absent. The day stays calibrated (frozen baseline snapshot
    // carried forward), but every sleep-dependent output must come back fresh/no-data.
    @Test
    fun `deleted only sleep on a calibrated date drops stale sleep and recommendation payload`() =
        runTest {
            val day = LocalDate.of(2026, 3, 10)
            val zoneId = ZoneId.of("UTC")
            val dayMs = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val prefs = UserPreferences(scoringZoneId = zoneId.id)

            coEvery { scoringHistoryRepository.getDailySummaryByDate(dayMs, zoneId) } returns
                previousWithStaleSleepPayload(day)
            // The only sleep session for this day was deleted: no overlapping/ending session.
            coEvery { sleepSessionDao.getOverlapping(any(), any()) } returns emptyList()
            coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } returns null

            val entitySlot = slot<DailySummaryEntity>()
            coEvery { dailySummaryDao.upsert(capture(entitySlot)) } returns Unit

            // Compute/publish twice -- idempotency: neither pass should resurrect the stale payload.
            repeat(2) {
                repo.computeAndPersistDailySummary(day, steps = null, prefs = prefs)
                assertFreshOutputForDeletedSleep(entitySlot, zoneId, day)
            }
        }

    private fun assertFreshOutputForDeletedSleep(
        entitySlot: CapturingSlot<DailySummaryEntity>,
        zoneId: ZoneId,
        day: LocalDate,
    ) {
        val fresh = DailySummaryMapper.toDomain(entitySlot.captured, zoneId)

        assertNull(fresh.sleepScore, "sleepScore must not survive a deleted sleep session")
        assertNull(fresh.nocturnalHrv, "nocturnalHrv must not survive a deleted sleep session")
        assertNull(fresh.sleepDurationMinutes, "sleepDurationMinutes must not survive deletion")
        assertNull(fresh.sRest, "restoration must not survive a deleted sleep session")
        assertFalse(
            RecoveryFlag.STRONG_RECOVERY_SIGNAL in fresh.readinessResult.recoveryFlags,
            "stale recovery flag must not survive a deleted sleep session",
        )
        assertTrue(
            RecoveryFlag.HRV_MISSING in fresh.readinessResult.recoveryFlags,
            "absent sleep must be explicitly flagged rather than silently blank",
        )
        assertNotEquals(
            WorkoutRecommendationState.EASY,
            fresh.workoutRecommendation?.decision?.state,
            "stale recommendation must not survive a deleted sleep session",
        )
        // The frozen calibration snapshot is still valid (H6/P2 declares it so): hrMax carries
        // forward via freshDaySummary rather than being silently dropped. (A successful calibrated
        // pass always re-stamps baselineCalculatedAtDate to the day it ran for -- see
        // AssembleDailySummaryUseCase.assembleCalibrated -- so today's date is the correct value.)
        assertEquals(day, fresh.baselineCalculatedAtDate)
        assertEquals(188f, fresh.hrMax)
        assertFalse(fresh.isCalibrating, "day must remain calibrated, not reset by the deletion")
    }

    // C3 (WP-13): a transient failure at any assembler boundary must yield DayAssembly.Unavailable,
    // which must never reach persistence -- the previous complete day, its canonical workout
    // values, and (at the DirtySummaryPublisher layer, covered by the instrumented test) its dirty
    // ticket must all stay untouched. computeAndPersistDailySummary surfaces this as a thrown
    // DayAssemblyUnavailableException rather than silently doing nothing.
    private fun setUpCanonicalWorkout(today: LocalDate, zoneId: ZoneId): WorkoutRecordEntity {
        val dayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val workout =
            WorkoutRecordEntity(
                id = "canonical-w1",
                startTime = dayStart + 3_600_000L,
                endTime = dayStart + 5_400_000L,
                exerciseType = "RUNNING",
                durationMinutes = 30,
                zone1Minutes = 5f,
                zone2Minutes = 20f,
                zone3Minutes = 5f,
                zone4Minutes = 0f,
                zone5Minutes = 0f,
                trimp = 40f,
                avgHr = 140f,
                modelTrimp = null,
            )
        coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns listOf(workout)
        every {
            computeWorkoutTrimpUseCase.execute(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(55f)
        return workout
    }

    private suspend fun assertUnavailableLeavesOldStateUntouched(today: LocalDate, zoneId: ZoneId) {
        assertFailsWith<DayAssemblyUnavailableException> {
            repo.computeAndPersistDailySummary(
                today,
                steps = null,
                prefs = UserPreferences(scoringZoneId = zoneId.id),
            )
        }
        coVerify(exactly = 0) { dailySummaryDao.upsert(any()) }
        coVerify(exactly = 0) { workoutDao.upsertAll(any()) }
    }

    @Test
    fun `base assembly failure leaves old summary and canonical workouts untouched`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.of("UTC")
            setUpCanonicalWorkout(today, zoneId)
            coEvery {
                weightRecordDao.getLatestUpTo(any())
            } throws RuntimeException("simulated base-assembly failure")

            assertUnavailableLeavesOldStateUntouched(today, zoneId)
        }

    // C3 fix round 1 (finding 3): CalibrationGate.isCalibrated performs a real Room read
    // (countEligibleSleepDaysThrough) between the wrapped base/readiness stages. Before this fix
    // it was called unwrapped, so a thrown exception here would propagate raw instead of degrading
    // to DayAssembly.Unavailable like every other stage -- this proves it now does, leaving prior
    // state untouched exactly like the other stage failures below.
    @Test
    fun `calibration gate failure leaves old summary and canonical workouts untouched`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.of("UTC")
            setUpCanonicalWorkout(today, zoneId)
            // Default setup() has getDailySummaryByDate return null, so isCalibrated has no frozen
            // snapshot to trust and must fall through to the DB read below.
            coEvery {
                scoringHistoryRepository.countEligibleSleepDaysThrough(any(), any())
            } throws RuntimeException("simulated calibration-gate failure")

            assertUnavailableLeavesOldStateUntouched(today, zoneId)
        }

    @Test
    fun `readiness assembly failure leaves old summary and canonical workouts untouched`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.of("UTC")
            setUpCanonicalWorkout(today, zoneId)
            // Forces the calibrated path (frozen snapshot trusted) so computeCalibratedSummary's
            // HRV-baseline call below is actually reached.
            coEvery { scoringHistoryRepository.getDailySummaryByDate(any(), zoneId) } returns
                DailySummary(date = today, baselineCalculatedAtDate = today.minusDays(1))
            coEvery {
                baselineComputer.computeHrvBaselineBetween(any(), any(), any(), any(), any(), any())
            } throws RuntimeException("simulated readiness-assembly failure")

            assertUnavailableLeavesOldStateUntouched(today, zoneId)
        }

    @Test
    fun `final assembly failure leaves old summary and canonical workouts untouched`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.of("UTC")
            setUpCanonicalWorkout(today, zoneId)
            coEvery {
                workoutDao.countUnbackfilledThrough(any(), any())
            } throws RuntimeException("simulated final-assembly failure")

            assertUnavailableLeavesOldStateUntouched(today, zoneId)
        }

    @Test
    fun `recommendation assembly failure leaves old summary and canonical workouts untouched`() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.of("UTC")
            setUpCanonicalWorkout(today, zoneId)
            val failingSleepSessionRepository =
                mockk<SleepSessionRepository>(relaxed = true) {
                    coEvery {
                        getInRange(any(), any())
                    } throws RuntimeException("simulated recommendation-assembly failure")
                }
            repo = createRepo(UnconfinedTestDispatcher(), failingSleepSessionRepository)

            assertUnavailableLeavesOldStateUntouched(today, zoneId)
        }

    private fun createWorkoutEntity(id: String, startMs: Long, endMs: Long): WorkoutRecordEntity =
        WorkoutRecordEntity(
            id = id,
            startTime = startMs,
            endTime = endMs,
            exerciseType = "RUNNING",
            durationMinutes = 30,
            zone1Minutes = 0f,
            zone2Minutes = 0f,
            zone3Minutes = 0f,
            zone4Minutes = 0f,
            zone5Minutes = 0f,
            trimp = 0f,
            avgHr = 0f,
        )

    @Test
    fun `workout load unavailable registers valid canonical impulses into walk forward fatigue context`() =
        runTest {
            val today = LocalDate.of(2026, 4, 15)
            val zoneId = ZoneId.of("UTC")
            val dayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val w1 = createWorkoutEntity("valid-w1", dayStart + 3_600_000L, dayStart + 5_400_000L)
            val w2 = createWorkoutEntity("missing-hr-w2", dayStart + 7_200_000L, dayStart + 9_000_000L)
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns listOf(w1, w2)
            coEvery { heartRateDao.getVisibleByTypeAndTimeRange(any(), any(), any()) } returns
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 0L,
                        timestampMs = w1.startTime + 60_000L,
                        beatsPerMinute = 140,
                        recordType = "EXERCISE",
                        sessionId = w1.id,
                    ),
                )

            every {
                computeWorkoutTrimpUseCase.execute(any(), any(), any(), any(), any(), any(), any())
            } returns Result.success(55f)

            val fatigueContext = WalkForwardFatigueContext(seedInputs = emptyList())
            val contexts = WalkForwardContexts(fatigue = fatigueContext)

            val exception =
                assertFailsWith<DayAssemblyUnavailableException> {
                    repo.computeAndPersistDailySummary(
                        today,
                        steps = null,
                        prefs = UserPreferences(scoringZoneId = zoneId.id),
                        contexts = contexts,
                    )
                }
            assertEquals(
                "Day assembly unavailable: ${DayAssemblyUnavailableReason.WORKOUT_LOAD_UNAVAILABLE}",
                exception.message,
            )

            coVerify(exactly = 0) { dailySummaryDao.upsert(any()) }
            coVerify(exactly = 0) { workoutDao.upsertAll(any()) }

            val registeredImpulses = fatigueContext.takeImpulsesThrough(Long.MAX_VALUE)
            assertEquals(1, registeredImpulses.size)
            assertEquals("valid-w1", registeredImpulses.first().workoutId)
            assertEquals(55f, registeredImpulses.first().trimp)
        }
}
