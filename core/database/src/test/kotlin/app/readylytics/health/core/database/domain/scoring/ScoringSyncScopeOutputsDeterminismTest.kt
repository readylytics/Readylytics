package app.readylytics.health.core.database.domain.scoring
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase

import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.SleepMetricsCollaborators
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepHrSample
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepStageDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.database.data.repository.BodyMetricsDataLoader
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringSeriesLoader
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.core.model.data.preferences.appliedTrainingReadinessConfig
import app.readylytics.health.core.model.domain.model.LoadSourceSelector
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.security.EncryptionManager
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import app.readylytics.health.core.database.data.repository.ScoringDayUseCases
import app.readylytics.health.core.database.data.repository.ScoringDataLoaders

@OptIn(ExperimentalCoroutinesApi::class)
class ScoringSyncScopeOutputsDeterminismTest {
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val targetDate: LocalDate = LocalDate.of(2026, 2, 15)
    private val prefs =
        UserPreferences(
            physiologyProfile = PhysiologyProfile.ACTIVE,
            goalSleepHours = 8f,
            maxHeartRate = 190,
            age = 32,
            gender = Gender.MALE,
            restingHrPercentile = 5,
            rasScalingFactor = 0.2f,
        )

    @Test
    fun `same target date yields identical outputs across 60d 365d 1y unlimited histories`() =
        runTest {
            val fixture = buildFixture(includeFutureSessions = false)

            val results =
                listOf(
                    computeForScope("60-day onboarding", fixture, scopeDays = 60),
                    computeForScope("365-day resync", fixture, scopeDays = 365),
                    computeForScope("1-year retention", fixture, scopeDays = 365),
                    computeForScope("Unlimited", fixture, scopeDays = null),
                )

            assertMatrixPopulated(results.first().summary)
            assertSameMatrix(results)
        }

    @Test
    fun `same target date yields identical outputs when extra future data is present before recompute`() =
        runTest {
            val baseFixture = buildFixture(includeFutureSessions = false)
            val futureFixture = buildFixture(includeFutureSessions = true)

            val results =
                listOf(
                    computeForScope("60-day onboarding", baseFixture, scopeDays = 60),
                    computeForScope("60-day onboarding + future", futureFixture, scopeDays = 60),
                    computeForScope("365-day resync", baseFixture, scopeDays = 365),
                    computeForScope("365-day resync + future", futureFixture, scopeDays = 365),
                    computeForScope("Unlimited", baseFixture, scopeDays = null),
                    computeForScope("Unlimited + future", futureFixture, scopeDays = null),
                )

            assertMatrixPopulated(results.first().summary)
            assertSameMatrix(results)
        }

    @Test
    fun `training readiness and legacy matrix is deterministic across every sync pathway and load source`() =
        runTest {
            for (sourceMode in LoadSourceMode.values()) {
                val sourcePrefs = prefs.copy(strainLoadSourceMode = sourceMode)
                val canonicalFixture =
                    buildFixture(includeFutureSessions = false).copy(preferences = sourcePrefs)
                val incremental =
                    computeForScope(
                        label = "incremental sync",
                        fixture = canonicalFixture,
                        scopeDays = 60,
                    )
                val results =
                    listOf(
                        incremental,
                        computeForScope("full resync", canonicalFixture, 365),
                        computeForScope("historical backfill", canonicalFixture, 365),
                        computeProjectionOnly("partial recompute", incremental.summary, sourcePrefs),
                        computeForScope(
                            label = "app restart",
                            fixture =
                                buildFixture(includeFutureSessions = false)
                                    .copy(preferences = sourcePrefs),
                            scopeDays = 60,
                        ),
                        computeForScope(
                            label = "reordered Health Connect ingestion",
                            fixture = canonicalFixture.reversedIngestionOrder(),
                            scopeDays = 60,
                        ),
                    )

                assertMatrixPopulated(results.first().summary)
                assertSameMatrix(results)
            }
        }

    private suspend fun computeProjectionOnly(
        label: String,
        existingSummary: DailySummary,
        preferences: UserPreferences,
    ): ScopeResult {
        val scoringHistoryRepository = mockk<ScoringHistoryRepository>()
        coEvery { scoringHistoryRepository.getDailySummariesSince(any(), any()) } returns listOf(existingSummary)
        val saved = slot<List<DailySummary>>()
        coEvery { scoringHistoryRepository.upsertDailySummaries(capture(saved), any()) } returns Unit
        val transactionRunner =
            object : TransactionRunner {
                override suspend fun <R> runInTransaction(block: suspend () -> R): R = block()
            }
        val scoringCalculator =
            CompositeScoringCalculator(
                SleepScoringStrategy(LoadScoringStrategy()),
                RasScoringStrategy(),
                LoadScoringStrategy(),
            )
        val projectionUseCase =
            TrainingReadinessProjectionRecomputeUseCase(
                scoringHistoryRepository = scoringHistoryRepository,
                transactionRunner = transactionRunner,
                computeTrainingReadiness = ComputeTrainingReadinessUseCase(scoringCalculator),
            )

        projectionUseCase.execute(
            startDate = targetDate,
            endDate = targetDate,
            zoneId = zoneId,
            config = preferences.appliedTrainingReadinessConfig(),
        )

        val summary = saved.captured.single()
        return ScopeResult(label, summary, advisorInput(summary, preferences.strainLoadSourceMode))
    }

    @Test
    fun `same target date keeps rounded sleep and readiness when live summary becomes frozen`() =
        runTest {
            val fixture = buildFixture(includeFutureSessions = false)
            val live = computeForScope("live", fixture, scopeDays = 60).summary
            val frozenReplay =
                computeForScope(
                    label = "frozen replay",
                    fixture = fixture,
                    scopeDays = 60,
                    existingTargetSummary = DailySummaryMapper.toEntity(live, zoneId),
                ).summary

            assertMatrixPopulated(live)
            assertEquals(
                live.sleepScore?.roundToInt(),
                frozenReplay.sleepScore?.roundToInt(),
                "Rounded sleepScore must not flip when a live-computed day is recomputed from its frozen summary.",
            )
            assertEquals(
                live.readinessWorkoutOnly?.roundToInt(),
                frozenReplay.readinessWorkoutOnly?.roundToInt(),
                "Rounded readinessWorkoutOnly must not flip when a live-computed day is recomputed " +
                    "from its frozen summary.",
            )
        }

    @Test
    fun `frozen baseline snapshot columns remain unchanged when target day is recomputed`() =
        runTest {
            val fixture = buildFixture(includeFutureSessions = false)
            val live = computeForScope("live", fixture, scopeDays = 60).summary
            val frozenSnapshot =
                live.copy(
                    hrvBaseline = 123,
                    hrvMuMssd = 4.321f,
                    hrvSigmaMssd = 0.321f,
                    rhrBpm = 52.5f,
                    rhrSigma = 1.75f,
                    baselineCalculatedAtDate = targetDate,
                )

            val recomputed =
                computeForScope(
                    label = "frozen snapshot replay",
                    fixture = fixture,
                    scopeDays = 365,
                    existingTargetSummary = DailySummaryMapper.toEntity(frozenSnapshot, zoneId),
                ).summary

            assertEquals(123, recomputed.hrvBaseline, "Frozen HRV display baseline must not be recomputed.")
            assertEquals(4.321f, recomputed.hrvMuMssd, "Frozen HRV mu must not be recomputed.")
            assertEquals(0.321f, recomputed.hrvSigmaMssd, "Frozen HRV sigma must not be recomputed.")
            assertEquals(52.5f, recomputed.rhrBpm, "Frozen RHR baseline must not be recomputed.")
            assertEquals(1.75f, recomputed.rhrSigma, "Frozen RHR sigma must not be recomputed.")
        }

    private suspend fun computeForScope(
        label: String,
        fixture: Fixture,
        scopeDays: Int?,
        existingTargetSummary: DailySummaryEntity? = null,
    ): ScopeResult {
        val preferences = fixture.preferences ?: prefs
        val cutoffMs =
            scopeDays?.let {
                targetDate
                    .minusDays(it.toLong())
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            }
                ?: Long.MIN_VALUE
        val scopedSessions = fixture.sessions.filter { it.startTime >= cutoffMs }.sortedBy { it.startTime }
        val scopedSessionIds = scopedSessions.map { it.id }.toSet()
        val scopedHrSamples =
            fixture.sleepHrBySession
                .filterKeys { it in scopedSessionIds }
                .mapValues { (_, values) -> values.sorted() }
        val scopedHeartRateRecords = fixture.heartRateRecords.filter { it.sessionId in scopedSessionIds }
        val scopedHrvSamples = fixture.hrvBySession.filterKeys { it in scopedSessionIds }

        val workoutDao = mockk<WorkoutDao>(relaxed = true)
        val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
        val sleepStageDao = mockk<SleepStageDao>(relaxed = true)
        val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        val heartRateDao = mockk<HeartRateDao>(relaxed = true)
        val hrvDao = mockk<HrvDao>(relaxed = true)
        val minuteBucketDao = mockk<MinuteBucketDao>(relaxed = true)
        val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
        val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
        val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
        val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
        val bodyTemperatureRecordDao = mockk<BodyTemperatureRecordDao>(relaxed = true)

        every { settingsRepo.userPreferences } returns flowOf(preferences)

        coEvery { sleepSessionDao.countSince(any()) } coAnswers {
            val fromMs = firstArg<Long>()
            scopedSessions.count { it.startTime >= fromMs }
        }
        coEvery { sleepSessionDao.getSince(any()) } coAnswers {
            val fromMs = firstArg<Long>()
            scopedSessions.filter { it.startTime >= fromMs }.sortedBy { it.startTime }
        }
        coEvery { sleepSessionDao.getBetween(any(), any()) } coAnswers {
            val fromMs = firstArg<Long>()
            val toMs = secondArg<Long>()
            scopedSessions
                .filter { it.startTime >= fromMs && it.endTime <= toMs }
                .sortedBy { it.startTime }
        }
        coEvery { sleepSessionDao.getSessionEndingInRange(any(), any()) } coAnswers {
            val fromMs = firstArg<Long>()
            val toMs = secondArg<Long>()
            scopedSessions
                .filter { it.endTime >= fromMs && it.endTime < toMs }
                .minByOrNull { it.endTime }
        }

        coEvery { heartRateDao.getByTimeRange(any(), any()) } coAnswers {
            val fromMs = firstArg<Long>()
            val toMs = secondArg<Long>()
            scopedHeartRateRecords
                .filter { it.timestampMs >= fromMs && it.timestampMs <= toMs }
                .sortedBy { it.timestampMs }
        }
        coEvery { heartRateDao.getAvgSleepHrForSessions(any()) } coAnswers {
            firstArg<List<String>>().associateWith { sessionId ->
                scopedHrSamples[sessionId]
                    ?.average()
                    ?.roundToInt()
                    ?: return@associateWith 0
            }
        }
        coEvery { heartRateDao.getSleepHrProjectionForSessions(any()) } coAnswers {
            firstArg<List<String>>()
                .flatMap { sessionId ->
                    scopedHrSamples[sessionId]
                        .orEmpty()
                        .map { bpm -> SleepHrSample(sessionId = sessionId, beatsPerMinute = bpm) }
                }.sortedWith(compareBy<SleepHrSample> { it.sessionId }.thenBy { it.beatsPerMinute })
        }
        coEvery { heartRateDao.getSleepHrSamplesForSession(any()) } coAnswers {
            scopedHrSamples[firstArg<String>()].orEmpty()
        }
        coEvery { heartRateDao.getAvgSleepHr(any()) } coAnswers {
            scopedHrSamples[firstArg<String>()]?.average()?.roundToInt()
        }
        coEvery { heartRateDao.getMinHrTimestamp(any()) } coAnswers {
            val sessionId = firstArg<String>()
            scopedHeartRateRecords
                .filter { it.sessionId == sessionId }
                .minWithOrNull(compareBy<HeartRateRecordEntity> { it.beatsPerMinute }.thenBy { it.timestampMs })
                ?.timestampMs
        }

        coEvery { hrvDao.getSleepRmssdForSession(any()) } coAnswers {
            scopedHrvSamples[firstArg<String>()].orEmpty()
        }
        coEvery { hrvDao.getRmssdInTimeRange(any(), any()) } coAnswers {
            val fromMs = firstArg<Long>()
            val toMs = secondArg<Long>()
            scopedSessions
                .filter { it.startTime >= fromMs && it.endTime <= toMs }
                .flatMap { scopedHrvSamples[it.id].orEmpty() }
        }
        coEvery { hrvDao.getSleepRmssdForSessionsMap(any()) } coAnswers {
            firstArg<List<String>>().associateWith { sessionId -> scopedHrvSamples[sessionId].orEmpty() }
        }

        val targetMidnightMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        coEvery { dailySummaryDao.getByDate(any()) } coAnswers {
            val dateMs = firstArg<Long>()
            if (dateMs == targetMidnightMs) existingTargetSummary else null
        }
        coEvery { dailySummaryDao.getByDates(any()) } returns emptyList()
        coEvery { dailySummaryDao.getSince(any()) } returns emptyList()

        coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns emptyList()
        coEvery { workoutDao.getTrimpPoints(any(), any()) } returns emptyList()
        coEvery { dailySummaryDao.getEverydayTrimpPoints(any(), any()) } returns emptyList()

        coEvery { weightRecordDao.getLatestUpTo(any()) } returns null
        coEvery { bodyFatRecordDao.getLatestUpTo(any()) } returns null
        coEvery { bloodPressureRecordDao.getLatestUpTo(any()) } returns null
        coEvery { oxygenSaturationRecordDao.getByTimeRange(any(), any()) } returns emptyList()
        coEvery { bodyTemperatureRecordDao.getByTimeRange(any(), any()) } returns emptyList()

        val scoringCalculator =
            CompositeScoringCalculator(
                SleepScoringStrategy(LoadScoringStrategy()),
                RasScoringStrategy(),
                LoadScoringStrategy(),
            )
        val scoringHistoryRepository =
            ScoringHistoryRepositoryImpl(
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                sleepSessionDao = sleepSessionDao,
                dailySummaryDao = dailySummaryDao,
                minuteBucketDao = minuteBucketDao,
            )
        val baselineComputer =
            BaselineComputer(
                scoringHistoryRepository = scoringHistoryRepository,
                scoringCalculator = scoringCalculator,
            )
        val scoringConfigFactory = ScoringConfigFactory()
        val encryptionManager = mockk<EncryptionManager>(relaxed = true)
        val currentNightHrvResolver = CurrentNightHrvResolver(scoringHistoryRepository)
        val sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository)
        val sleepNadirAnalyzer = SleepNadirAnalyzer(scoringCalculator)
        val coverageValidator = HrCoverageValidator()
        val sleepSessionRepository = SleepSessionRepositoryImpl(sleepSessionDao, sleepStageDao)
        val circadianConsistencyRepository =
            CircadianConsistencyRepository(sleepSessionRepository, settingsRepo, encryptionManager)
        val sleepModifierResolver = SleepModifierResolver(sleepSessionRepository, circadianConsistencyRepository)
        val computeSleepMetricsUseCase =
            ComputeSleepMetricsUseCase(
                collaborators =
                    SleepMetricsCollaborators(
                        baselineComputer = baselineComputer,
                        scoringHistoryRepository = scoringHistoryRepository,
                        scoringCalculator = scoringCalculator,
                        scoringConfigFactory = scoringConfigFactory,
                        encryptionManager = encryptionManager,
                        hrvResolver = currentNightHrvResolver,
                        sleepPercentileRhrCalculator = sleepPercentileRhrCalculator,
                        nadirAnalyzer = sleepNadirAnalyzer,
                        coverageValidator = coverageValidator,
                        sleepModifierResolver = sleepModifierResolver,
                    ),
            )

        val dataLoader =
            ScoringDayDataLoader(
                workoutDao,
                sleepSessionDao,
                dailySummaryDao,
                heartRateDao,
                minuteBucketDao,
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                bodyTemperatureRecordDao,
            )
        val bodyMetricsDataLoader =
            BodyMetricsDataLoader(
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                bodyTemperatureRecordDao,
            )
        val seriesLoader = ScoringSeriesLoader(workoutDao, dailySummaryDao)
        val buildLoadSeriesUseCase = BuildLoadSeriesUseCase(scoringCalculator)
        val resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(baselineComputer)
        val assembleDailySummaryUseCase = AssembleDailySummaryUseCase()
        val readinessSummaryCoordinator =
            ReadinessSummaryCoordinator(
                dataLoader = dataLoader,
                seriesLoader = seriesLoader,
                scoringHistoryRepository = scoringHistoryRepository,
                baselineComputer = baselineComputer,
                buildLoadSeriesUseCase = buildLoadSeriesUseCase,
                computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                resolveDailyBaselinesUseCase = resolveDailyBaselinesUseCase,
                assembleDailySummaryUseCase = assembleDailySummaryUseCase,
            )

        val repo =
            ScoringRepositoryImpl(
                loaders = ScoringDataLoaders(
                    dataLoader,
                    bodyMetricsDataLoader,
                    seriesLoader,
                ),
                settingsRepo = settingsRepo,
                baselineComputer = baselineComputer,
                scoringConfigFactory = scoringConfigFactory,
                useCases =
                    ScoringDayUseCases(
                        ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
                        ComputeResidualFatigueUseCase(),
                        resolveDailyBaselinesUseCase,
                        AssembleEverydayLoadInputUseCase(),
                        ComputeTrainingReadinessUseCase(scoringCalculator),
                    ),
                scoringHistoryRepository = scoringHistoryRepository,
                readinessSummaryCoordinator = readinessSummaryCoordinator,
                defaultDispatcher = UnconfinedTestDispatcher(),
            )

        val summary = repo.computeDailySummary(targetDate)
        return ScopeResult(
            label = label,
            summary = summary,
            advisorInput = advisorInput(summary, preferences.strainLoadSourceMode),
        )
    }

    private fun assertMatrixPopulated(summary: DailySummary) {
        assertNotNull(summary.sleepScore, "sleepScore should be populated by the determinism fixture")
        // US-03: readiness now lives in the workout-only variant column; legacy readinessScore is frozen.
        assertNotNull(
            summary.readinessWorkoutOnly,
            "readinessWorkoutOnly should be populated by the determinism fixture",
        )
        assertNotNull(summary.residualFatigue, "residualFatigue should be populated by the determinism fixture")
        assertNotNull(summary.acuteLoadRecovery, "acuteLoadRecovery should be populated by the determinism fixture")
        assertNotNull(
            summary.trainingLoadReadinessWorkoutOnly,
            "trainingLoadReadinessWorkoutOnly should be populated by the determinism fixture",
        )
        assertNotNull(
            summary.trainingLoadReadinessEverydayHr,
            "trainingLoadReadinessEverydayHr should be populated by the determinism fixture",
        )
        assertNotNull(
            summary.trainingReadinessWorkoutOnly,
            "trainingReadinessWorkoutOnly should be populated by the determinism fixture",
        )
        assertNotNull(
            summary.trainingReadinessEverydayHr,
            "trainingReadinessEverydayHr should be populated by the determinism fixture",
        )
        assertNotNull(summary.rhrBpm, "rhrBpm should be populated by the determinism fixture")
        assertNotNull(summary.rhrSigma, "rhrSigma should be populated by the determinism fixture")
        assertNotNull(summary.hrvMuMssd, "hrvMuMssd should be populated by the determinism fixture")
        assertNotNull(summary.hrvSigmaMssd, "hrvSigmaMssd should be populated by the determinism fixture")
        assertNotNull(summary.restingHeartRate, "restingHeartRate should be populated by the determinism fixture")
        assertNotNull(summary.nocturnalHrv, "nocturnalHrv should be populated by the determinism fixture")
        assertNotNull(
            summary.baselineObservationCount,
            "baselineObservationCount should be populated by the determinism fixture",
        )
    }

    private fun assertSameMatrix(results: List<ScopeResult>) {
        val fields: List<Pair<String, (DailySummary) -> Any?>> =
            listOf(
                "sleepScore" to { it.sleepScore },
                "trimpWorkoutOnly" to { it.trimpWorkoutOnly },
                "trimpEverydayHr" to { it.trimpEverydayHr },
                "atlWorkoutOnly" to { it.atlWorkoutOnly },
                "atlEverydayHr" to { it.atlEverydayHr },
                "ctlWorkoutOnly" to { it.ctlWorkoutOnly },
                "ctlEverydayHr" to { it.ctlEverydayHr },
                "strainRatioWorkoutOnly" to { it.strainRatioWorkoutOnly },
                "strainRatioEverydayHr" to { it.strainRatioEverydayHr },
                "loadScoreWorkoutOnly" to { it.loadScoreWorkoutOnly },
                "loadScoreEverydayHr" to { it.loadScoreEverydayHr },
                "readinessWorkoutOnly" to { it.readinessWorkoutOnly },
                "readinessEverydayHr" to { it.readinessEverydayHr },
                "residualFatigue" to { it.residualFatigue },
                "acuteLoadRecovery" to { it.acuteLoadRecovery },
                "trainingLoadReadinessWorkoutOnly" to { it.trainingLoadReadinessWorkoutOnly },
                "trainingLoadReadinessEverydayHr" to { it.trainingLoadReadinessEverydayHr },
                "trainingReadinessWorkoutOnly" to { it.trainingReadinessWorkoutOnly },
                "trainingReadinessEverydayHr" to { it.trainingReadinessEverydayHr },
                "rhrBpm" to { it.rhrBpm },
                "rhrSigma" to { it.rhrSigma },
                "hrvMuMssd" to { it.hrvMuMssd },
                "hrvSigmaMssd" to { it.hrvSigmaMssd },
                "restingHeartRate" to { it.restingHeartRate },
                "nocturnalHrv" to { it.nocturnalHrv },
                "baselineObservationCount" to { it.baselineObservationCount },
                "zLnHrv" to { it.zLnHrv },
                "zRhr" to { it.zRhr },
                "sRest" to { it.sRest },
            )

        val baseline = results.first()
        for ((fieldName, selector) in fields) {
            val expected = selector(baseline.summary)
            val divergent =
                results
                    .map { it.label to selector(it.summary) }
                    .filter { (_, actual) -> actual != expected }
            if (divergent.isNotEmpty()) {
                val details =
                    results.joinToString(separator = "\n") { result ->
                        "${result.label}: ${selector(result.summary)}"
                    }
                fail("First divergent field: $fieldName\n$details")
            }
        }
        val expectedAdvisorInput = baseline.advisorInput
        val divergentAdvisorInputs = results.filter { it.advisorInput != expectedAdvisorInput }
        if (divergentAdvisorInputs.isNotEmpty()) {
            val details = results.joinToString(separator = "\n") { "${it.label}: ${it.advisorInput}" }
            fail("Training Advisor inputs diverged\n$details")
        }
    }

    private fun advisorInput(
        summary: DailySummary,
        sourceMode: LoadSourceMode,
    ): AdvisorInput =
        AdvisorInput(
            readiness = LoadSourceSelector.selectReadiness(summary, sourceMode),
            loadScore = LoadSourceSelector.selectLoadScore(summary, sourceMode),
            trimp = LoadSourceSelector.selectTrimp(summary, sourceMode),
            atl = LoadSourceSelector.selectAtl(summary, sourceMode),
            ctl = LoadSourceSelector.selectCtl(summary, sourceMode),
            strainRatio = LoadSourceSelector.selectStrainRatio(summary, sourceMode),
            sleepScore = summary.sleepScore,
            restoration = summary.sRest,
            recoveryFlags = summary.recoveryFlags,
        )

    private fun buildFixture(includeFutureSessions: Boolean): Fixture {
        val sessions = mutableListOf<SleepSessionEntity>()
        val sleepHrBySession = mutableMapOf<String, List<Int>>()
        val hrvBySession = mutableMapOf<String, List<Float>>()
        val heartRateRecords = mutableListOf<HeartRateRecordEntity>()

        fun addSession(
            sessionId: String,
            dayOffset: Long,
            lowHr: Int,
            hrvBase: Float,
        ) {
            val end =
                targetDate
                    .minusDays(dayOffset)
                    .atTime(6, 30)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val start = end - 8 * 60 * 60 * 1000L
            val session =
                SleepSessionEntity(
                    id = sessionId,
                    startTime = start,
                    endTime = end,
                    durationMinutes = 480,
                    efficiency = 92f,
                    deepSleepMinutes = 105,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 255,
                    awakeMinutes = 30,
                    startZoneOffsetSeconds = 0,
                    endZoneOffsetSeconds = 0,
                    deviceName = "Pixel",
                )
            val bpmCurve =
                listOf(
                    lowHr + 8,
                    lowHr + 6,
                    lowHr + 5,
                    lowHr + 4,
                    lowHr + 2,
                    lowHr,
                    lowHr + 1,
                    lowHr + 2,
                    lowHr + 3,
                    lowHr + 4,
                    lowHr + 5,
                    lowHr + 6,
                )
            val stepMs = (session.endTime - session.startTime) / (bpmCurve.size - 1)

            sessions += session
            sleepHrBySession[sessionId] = bpmCurve.sorted()
            hrvBySession[sessionId] = listOf(hrvBase, hrvBase + 2f, hrvBase + 4f)
            heartRateRecords +=
                bpmCurve.mapIndexed { index, bpm ->
                    HeartRateRecordEntity(
                        sourceRecordRef = index.toLong() + 1,
                        timestampMs = session.startTime + stepMs * index,
                        beatsPerMinute = bpm,
                        recordType = "SLEEP",
                        sessionId = sessionId,
                        deviceName = "Pixel",
                    )
                }
        }

        for (offset in 0L..60L) {
            val lowHr = if (offset == 0L) 51 else 48 + (offset % 6).toInt()
            val hrvBase = if (offset == 0L) 66f else 48f + (offset % 9).toFloat()
            addSession("day_$offset", offset, lowHr, hrvBase)
        }
        for (offset in 61L..365L) {
            addSession("day_$offset", offset, 44 + (offset % 5).toInt(), 42f + (offset % 7).toFloat())
        }
        for (offset in 366L..500L) {
            addSession("day_$offset", offset, 41 + (offset % 4).toInt(), 38f + (offset % 6).toFloat())
        }
        if (includeFutureSessions) {
            for (offset in -5L..-1L) {
                addSession(
                    "future_${-offset}",
                    offset,
                    70 + offset.toInt().absoluteValue,
                    82f + offset.toFloat().absoluteValue,
                )
            }
        }

        return Fixture(
            sessions = sessions.sortedBy { it.startTime },
            sleepHrBySession = sleepHrBySession,
            hrvBySession = hrvBySession,
            heartRateRecords = heartRateRecords.sortedBy { it.timestampMs },
        )
    }

    private data class Fixture(
        val sessions: List<SleepSessionEntity>,
        val sleepHrBySession: Map<String, List<Int>>,
        val hrvBySession: Map<String, List<Float>>,
        val heartRateRecords: List<HeartRateRecordEntity>,
        val preferences: UserPreferences? = null,
    ) {
        fun reversedIngestionOrder(): Fixture =
            copy(
                sessions = sessions.reversed(),
                sleepHrBySession = sleepHrBySession.entries.reversed().associate { it.toPair() },
                hrvBySession = hrvBySession.entries.reversed().associate { it.toPair() },
                heartRateRecords = heartRateRecords.reversed(),
            )
    }

    private data class AdvisorInput(
        val readiness: Float?,
        val loadScore: Float?,
        val trimp: Float?,
        val atl: Float?,
        val ctl: Float?,
        val strainRatio: Float?,
        val sleepScore: Float?,
        val restoration: Float?,
        val recoveryFlags: Set<app.readylytics.health.core.model.domain.model.RecoveryFlag>,
    )

    private data class ScopeResult(
        val label: String,
        val summary: DailySummary,
        val advisorInput: AdvisorInput,
    )
}
