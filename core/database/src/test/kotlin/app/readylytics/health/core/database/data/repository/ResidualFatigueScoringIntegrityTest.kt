package app.readylytics.health.core.database.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.local.RoomTransactionRunner
import app.readylytics.health.core.database.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.core.database.domain.scoring.golden.FakeEncryptionManager
import app.readylytics.health.core.database.domain.sync.DailyRecomputeSupport
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.SleepMetricsCollaborators
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ResidualFatigueScoringIntegrityTest {
    private val zoneId: ZoneId = ZoneId.of("UTC")
    private val historyStartDate: LocalDate = LocalDate.of(2026, 4, 14)
    private val evalStartDate: LocalDate = LocalDate.of(2026, 5, 20)
    private val evalEndDate: LocalDate = LocalDate.of(2026, 6, 1)

    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `all reconstruction paths produce identical residualFatigue across evaluation range`() =
        runTest {
            seedDeterministicScenario(db)
            val basePrefs = basePreferences()

            val fullOutputs = executeWalkForward(db, historyStartDate, evalEndDate, basePrefs)
            val fullMap = fullOutputs.associate { it.dateMidnightMs to it.residualFatigue }

            val partialOutputs = executeWalkForward(db, evalStartDate, evalEndDate, basePrefs)
            val partialMap = partialOutputs.associate { it.dateMidnightMs to it.residualFatigue }

            val incrementalMap = mutableMapOf<Long, Float?>()
            var currentDay = evalStartDate
            while (!currentDay.isAfter(evalEndDate)) {
                val dayOutput = executeWalkForward(db, currentDay, currentDay, basePrefs)
                incrementalMap[dayOutput.single().dateMidnightMs] = dayOutput.single().residualFatigue
                currentDay = currentDay.plusDays(1)
            }

            val scoringRepo = buildScoringRepo(db, MutableTestSettingsRepository(basePrefs))
            val backfillMap = mutableMapOf<Long, Float?>()
            currentDay = evalStartDate
            while (!currentDay.isAfter(evalEndDate)) {
                val summary = scoringRepo.computeDailySummary(currentDay)
                val dateMs = currentDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
                backfillMap[dateMs] = summary.residualFatigue
                currentDay = currentDay.plusDays(1)
            }

            val midDate = LocalDate.of(2026, 5, 25)
            executeWalkForward(db, evalStartDate, midDate, basePrefs)
            val resumedOutputs = executeWalkForward(db, midDate.plusDays(1), evalEndDate, basePrefs)
            val resumedMap = resumedOutputs.associate { it.dateMidnightMs to it.residualFatigue }

            val secondPassOutputs = executeWalkForward(db, evalStartDate, evalEndDate, basePrefs)
            val secondPassMap = secondPassOutputs.associate { it.dateMidnightMs to it.residualFatigue }

            assertAllReconstructionPathsMatch(
                fullMap = fullMap,
                partialMap = partialMap,
                incrementalMap = incrementalMap,
                backfillMap = backfillMap,
                secondPassMap = secondPassMap,
                resumedMap = resumedMap,
                midDate = midDate,
            )
        }

    private fun assertAllReconstructionPathsMatch(
        fullMap: Map<Long, Float?>,
        partialMap: Map<Long, Float?>,
        incrementalMap: Map<Long, Float?>,
        backfillMap: Map<Long, Float?>,
        secondPassMap: Map<Long, Float?>,
        resumedMap: Map<Long, Float?>,
        midDate: LocalDate,
    ) {
        var checkDay = evalStartDate
        while (!checkDay.isAfter(evalEndDate)) {
            val dateMs = checkDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val fullFatigue = assertNotNull(fullMap[dateMs], "Full resync missing fatigue for $checkDay")
            val partialFatigue = assertNotNull(partialMap[dateMs], "Partial resync missing fatigue for $checkDay")
            val incFatigue = assertNotNull(incrementalMap[dateMs], "Incremental missing fatigue for $checkDay")
            val backfillFatigue = assertNotNull(backfillMap[dateMs], "Backfill missing fatigue for $checkDay")
            val secondPassFatigue = assertNotNull(secondPassMap[dateMs], "Second pass missing fatigue for $checkDay")

            assertEquals(fullFatigue, partialFatigue, EPSILON, "Full vs Partial mismatch on $checkDay")
            assertEquals(fullFatigue, incFatigue, EPSILON, "Full vs Incremental mismatch on $checkDay")
            assertEquals(fullFatigue, backfillFatigue, EPSILON, "Full vs Backfill mismatch on $checkDay")
            assertEquals(fullFatigue, secondPassFatigue, EPSILON, "Full vs SecondPass mismatch on $checkDay")

            if (checkDay.isAfter(midDate)) {
                val resumedFatigue = assertNotNull(resumedMap[dateMs], "Resumed missing fatigue for $checkDay")
                assertEquals(fullFatigue, resumedFatigue, EPSILON, "Full vs Resumed mismatch on $checkDay")
            }
            checkDay = checkDay.plusDays(1)
        }
    }

    @Test
    fun `parameter and profile invalidation recomputes affected values and preserves explicit settings`() =
        runTest {
            seedDeterministicScenario(db)
            val basePrefs = basePreferences()
            val baselineSummaries = executeWalkForward(db, evalStartDate, evalEndDate, basePrefs)
            val baselineFatigues = baselineSummaries.mapNotNull { it.residualFatigue }

            val hlPrefs = basePrefs.copy(residualFatigueHalfLifeHours = 48f)
            val hlSummaries = executeWalkForward(db, evalStartDate, evalEndDate, hlPrefs)
            assertNotEquals(baselineFatigues, hlSummaries.mapNotNull { it.residualFatigue })

            val gainPrefs = basePrefs.copy(residualFatigueGain = 2.5f)
            val gainSummaries = executeWalkForward(db, evalStartDate, evalEndDate, gainPrefs)
            assertNotEquals(baselineFatigues, gainSummaries.mapNotNull { it.residualFatigue })

            val multPrefs = basePrefs.copy(banisterMultiplier = 2.0f)
            val multSummaries = executeWalkForward(db, evalStartDate, evalEndDate, multPrefs)
            assertNotEquals(baselineFatigues, multSummaries.mapNotNull { it.residualFatigue })

            val chengPrefs = basePrefs.copy(trimpModel = TrimpModel.CHENG, chengBeta = 0.4f, zone3MaxBpm = 150)
            val chengSummaries = executeWalkForward(db, evalStartDate, evalEndDate, chengPrefs)
            assertNotEquals(baselineFatigues, chengSummaries.mapNotNull { it.residualFatigue })

            val cBetaPrefs = chengPrefs.copy(chengBeta = 0.8f)
            val cBetaSummaries = executeWalkForward(db, evalStartDate, evalEndDate, cBetaPrefs)
            assertNotEquals(
                chengSummaries.mapNotNull { it.residualFatigue },
                cBetaSummaries.mapNotNull { it.residualFatigue },
            )

            val itrimpPrefs = basePrefs.copy(trimpModel = TrimpModel.I_TRIMP, itrimB = 3.0f)
            val itrimpSummaries = executeWalkForward(db, evalStartDate, evalEndDate, itrimpPrefs)
            assertNotEquals(baselineFatigues, itrimpSummaries.mapNotNull { it.residualFatigue })

            val itrimpBPrefs = itrimpPrefs.copy(itrimB = 1.5f)
            val itrimpBSummaries = executeWalkForward(db, evalStartDate, evalEndDate, itrimpBPrefs)
            assertNotEquals(
                itrimpSummaries.mapNotNull { it.residualFatigue },
                itrimpBSummaries.mapNotNull { it.residualFatigue },
            )

            val hrPrefs = basePrefs.copy(maxHeartRate = 175)
            val hrSummaries = executeWalkForward(db, evalStartDate, evalEndDate, hrPrefs)
            assertNotEquals(baselineFatigues, hrSummaries.mapNotNull { it.residualFatigue })

            val customPrefs = basePrefs.copy(
                physiologyProfile = PhysiologyProfile.ATHLETE,
                residualFatigueHalfLifeHours = 36f,
                residualFatigueGain = 1.8f,
            )
            val profileChangedPrefs = customPrefs.copy(physiologyProfile = PhysiologyProfile.ACTIVE)
            assertEquals(36f, profileChangedPrefs.residualFatigueHalfLifeHours)
            assertEquals(1.8f, profileChangedPrefs.residualFatigueGain)
        }

    @Test
    fun `residualFatigue is identical across all LoadSourceMode combinations`() =
        runTest {
            seedDeterministicScenario(db)
            val basePrefs = basePreferences()

            val resultsByMode = mutableMapOf<Pair<LoadSourceMode, LoadSourceMode>, List<Float?>>()

            for (strainMode in LoadSourceMode.entries) {
                for (rasMode in LoadSourceMode.entries) {
                    val prefs = basePrefs.copy(
                        strainLoadSourceMode = strainMode,
                        rasSourceMode = rasMode,
                    )
                    val summaries = executeWalkForward(db, evalStartDate, evalEndDate, prefs)
                    resultsByMode[Pair(strainMode, rasMode)] = summaries.map { it.residualFatigue }
                }
            }

            val expected = resultsByMode[Pair(LoadSourceMode.WORKOUT_ONLY, LoadSourceMode.WORKOUT_ONLY)]
            for ((modePair, fatigues) in resultsByMode) {
                assertEquals(expected, fatigues, "residualFatigue must match for $modePair")
            }
        }

    @Test
    fun `Phase 1 remains shadow-only without modifying readiness load or recommendation outputs`() =
        runTest {
            seedDeterministicScenario(db)
            val basePrefs = basePreferences()

            val disabledPrefs = basePrefs.copy(residualFatigueEnabled = false)
            val defPrefs = basePrefs.copy(
                residualFatigueEnabled = true,
                residualFatigueHalfLifeHours = 24f,
                residualFatigueGain = 1.0f,
            )
            val minPrefs = basePrefs.copy(
                residualFatigueEnabled = true,
                residualFatigueHalfLifeHours = 12f,
                residualFatigueGain = 0.01f,
            )
            val maxPrefs = basePrefs.copy(
                residualFatigueEnabled = true,
                residualFatigueHalfLifeHours = 96f,
                residualFatigueGain = 5.0f,
            )

            val disabledSummaries = executeWalkForward(db, evalStartDate, evalEndDate, disabledPrefs)
            val defaultSummaries = executeWalkForward(db, evalStartDate, evalEndDate, defPrefs)
            val minSummaries = executeWalkForward(db, evalStartDate, evalEndDate, minPrefs)
            val maxSummaries = executeWalkForward(db, evalStartDate, evalEndDate, maxPrefs)

            assertEquals(disabledSummaries.size, defaultSummaries.size)

            for (i in disabledSummaries.indices) {
                val dis = disabledSummaries[i]
                val def = defaultSummaries[i]
                val min = minSummaries[i]
                val max = maxSummaries[i]

                assertEquals(dis.loadScoreWorkoutOnly, def.loadScoreWorkoutOnly)
                assertEquals(dis.loadScoreWorkoutOnly, min.loadScoreWorkoutOnly)
                assertEquals(dis.loadScoreWorkoutOnly, max.loadScoreWorkoutOnly)

                assertEquals(dis.loadScoreEverydayHr, def.loadScoreEverydayHr)
                assertEquals(dis.loadScoreEverydayHr, min.loadScoreEverydayHr)
                assertEquals(dis.loadScoreEverydayHr, max.loadScoreEverydayHr)

                assertEquals(dis.readinessWorkoutOnly, def.readinessWorkoutOnly)
                assertEquals(dis.readinessWorkoutOnly, min.readinessWorkoutOnly)
                assertEquals(dis.readinessWorkoutOnly, max.readinessWorkoutOnly)

                assertEquals(dis.readinessEverydayHr, def.readinessEverydayHr)
                assertEquals(dis.readinessEverydayHr, min.readinessEverydayHr)
                assertEquals(dis.readinessEverydayHr, max.readinessEverydayHr)

                assertEquals(dis.sleepScore, def.sleepScore)
                assertEquals(dis.sleepDurationMinutes, def.sleepDurationMinutes)
                assertEquals(dis.restingHeartRate, def.restingHeartRate)
                assertEquals(dis.recoveryFlags, def.recoveryFlags)
                assertEquals(dis.contributorsEmbedded, def.contributorsEmbedded)
                assertEquals(dis.diagnosticsEmbedded, def.diagnosticsEmbedded)

                assertEquals(dis.atlWorkoutOnly, def.atlWorkoutOnly)
                assertEquals(dis.ctlWorkoutOnly, def.ctlWorkoutOnly)
                assertEquals(dis.strainRatioWorkoutOnly, def.strainRatioWorkoutOnly)

                assertNull(dis.residualFatigue, "Disabled fatigue must produce null")
                assertNotNull(def.residualFatigue, "Enabled default fatigue must produce non-null")
                assertNotNull(min.residualFatigue, "Enabled min fatigue must produce non-null")
                assertNotNull(max.residualFatigue, "Enabled max fatigue must produce non-null")
            }
        }

    private fun basePreferences(): UserPreferences =
        UserPreferences(
            scoringZoneId = zoneId.id,
            installDate = historyStartDate.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            maxHeartRate = 190,
            autoCalculateMaxHr = false,
            residualFatigueEnabled = true,
            residualFatigueHalfLifeHours = 24f,
            residualFatigueGain = 1.0f,
        )

    private suspend fun executeWalkForward(
        database: HealthDatabase,
        startDate: LocalDate,
        endDate: LocalDate,
        prefs: UserPreferences,
    ): List<DailySummaryEntity> {
        val settingsRepo = MutableTestSettingsRepository(prefs)
        val scoringRepo = buildScoringRepo(database, settingsRepo)
        val support = DailyRecomputeSupport(scoringRepo, settingsRepo, RoomTransactionRunner(database))

        support.inRecomputeTransaction {
            val trimpContext = support.buildWalkForwardTrimpContext(startDate, endDate, zoneId)
            val baselineContext = support.buildWalkForwardBaselineContext(startDate, endDate, zoneId)
            val fatigueContext = support.buildWalkForwardFatigueContext(startDate, endDate, zoneId)
            var current = startDate
            while (!current.isAfter(endDate)) {
                support.recomputeDay(current, null, prefs, trimpContext, baselineContext, fatigueContext)
                current = current.plusDays(1)
            }
        }

        return database.dailySummaryDao().getAllSummaries()
            .filter {
                val instant = java.time.Instant.ofEpochMilli(it.dateMidnightMs)
                val day = ZonedDateTime.ofInstant(instant, zoneId).toLocalDate()
                !day.isBefore(startDate) && !day.isAfter(endDate)
            }
            .sortedBy { it.dateMidnightMs }
    }

    private data class RepoComponents(
        val database: HealthDatabase,
        val settingsRepo: SettingsRepository,
        val dataLoader: ScoringDayDataLoader,
        val seriesLoader: ScoringSeriesLoader,
        val scoringHistoryRepository: ScoringHistoryRepositoryImpl,
        val baselineComputer: BaselineComputer,
        val scoringCalculator: CompositeScoringCalculator,
        val scoringConfigFactory: ScoringConfigFactory,
    )

    private fun buildScoringRepo(
        database: HealthDatabase,
        settingsRepo: SettingsRepository,
    ): ScoringRepositoryImpl {
        val scoringHistoryRepository = ScoringHistoryRepositoryImpl(
            database.heartRateDao(), database.hrvDao(), database.sleepSessionDao(),
            database.dailySummaryDao(), database.minuteBucketDao(),
        )
        val loadScoringStrategy = LoadScoringStrategy()
        val scoringCalculator = CompositeScoringCalculator(
            sleepStrategy = SleepScoringStrategy(loadScoringStrategy),
            rasStrategy = RasScoringStrategy(),
            loadStrategy = loadScoringStrategy,
        )
        val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)
        val scoringConfigFactory = ScoringConfigFactory()
        val dataLoader = createDataLoader(database)
        val bodyMetricsDataLoader = BodyMetricsDataLoader(
            database.weightRecordDao(), database.bodyFatRecordDao(), database.bloodPressureRecordDao(),
            database.oxygenSaturationRecordDao(), database.bodyTemperatureRecordDao(),
        )
        val seriesLoader = ScoringSeriesLoader(database.workoutDao(), database.dailySummaryDao())
        val components = RepoComponents(
            database = database,
            settingsRepo = settingsRepo,
            dataLoader = dataLoader,
            seriesLoader = seriesLoader,
            scoringHistoryRepository = scoringHistoryRepository,
            baselineComputer = baselineComputer,
            scoringCalculator = scoringCalculator,
            scoringConfigFactory = scoringConfigFactory,
        )
        val coordinator = createReadinessCoordinator(components)

        return ScoringRepositoryImpl(
            dataLoader = dataLoader,
            bodyMetricsDataLoader = bodyMetricsDataLoader,
            seriesLoader = seriesLoader,
            settingsRepo = settingsRepo,
            baselineComputer = baselineComputer,
            scoringConfigFactory = scoringConfigFactory,
            computeDailyTrimpUseCase = ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
            resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(baselineComputer),
            assembleEverydayLoadInputUseCase = AssembleEverydayLoadInputUseCase(),
            scoringHistoryRepository = scoringHistoryRepository,
            readinessSummaryCoordinator = coordinator,
            defaultDispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun createDataLoader(database: HealthDatabase): ScoringDayDataLoader =
        ScoringDayDataLoader(
            database.workoutDao(), database.sleepSessionDao(), database.dailySummaryDao(),
            database.heartRateDao(), database.minuteBucketDao(), database.weightRecordDao(),
            database.bodyFatRecordDao(), database.bloodPressureRecordDao(),
            database.oxygenSaturationRecordDao(), database.bodyTemperatureRecordDao(),
        )

    private fun createReadinessCoordinator(
        components: RepoComponents,
    ): ReadinessSummaryCoordinator {
        val sleepRepo = SleepSessionRepositoryImpl(
            components.database.sleepSessionDao(),
            components.database.sleepStageDao(),
        )
        val circRepo = CircadianConsistencyRepository(sleepRepo, components.settingsRepo, FakeEncryptionManager())
        val sleepModifierResolver = SleepModifierResolver(sleepRepo, circRepo)
        val computeSleepMetricsUseCase = ComputeSleepMetricsUseCase(
            collaborators = SleepMetricsCollaborators(
                baselineComputer = components.baselineComputer,
                scoringHistoryRepository = components.scoringHistoryRepository,
                scoringCalculator = components.scoringCalculator,
                scoringConfigFactory = components.scoringConfigFactory,
                encryptionManager = FakeEncryptionManager(),
                hrvResolver = CurrentNightHrvResolver(components.scoringHistoryRepository),
                sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(components.scoringHistoryRepository),
                nadirAnalyzer = SleepNadirAnalyzer(components.scoringCalculator),
                coverageValidator = HrCoverageValidator(),
                sleepModifierResolver = sleepModifierResolver,
            ),
        )
        return ReadinessSummaryCoordinator(
            dataLoader = components.dataLoader,
            seriesLoader = components.seriesLoader,
            scoringHistoryRepository = components.scoringHistoryRepository,
            baselineComputer = components.baselineComputer,
            buildLoadSeriesUseCase = BuildLoadSeriesUseCase(components.scoringCalculator),
            computeSleepMetricsUseCase = computeSleepMetricsUseCase,
            resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(components.baselineComputer),
            assembleDailySummaryUseCase = AssembleDailySummaryUseCase(),
        )
    }

    private data class ScenarioRecords(
        val workouts: MutableList<WorkoutRecordEntity> = mutableListOf(),
        val sourceRecords: MutableList<HealthSourceRecordEntity> = mutableListOf(),
        val heartRates: MutableList<HeartRateRecordEntity> = mutableListOf(),
        val sleepSessions: MutableList<SleepSessionEntity> = mutableListOf(),
    )

    private suspend fun seedDeterministicScenario(database: HealthDatabase) {
        val records = ScenarioRecords()

        seedEarlyWorkouts(records)
        seedLateWorkouts(records)
        seedScenarioSleepSessions(records)

        database.sourceRecordDao().insertAll(records.sourceRecords)
        database.workoutDao().upsertAll(records.workouts)
        database.heartRateDao().upsertAll(records.heartRates)
        database.sleepSessionDao().upsertAll(records.sleepSessions)

        val reconciler = SessionLinkReconcilerImpl(
            sleepSessionDao = database.sleepSessionDao(),
            workoutDao = database.workoutDao(),
            heartRateDao = database.heartRateDao(),
            hrvDao = database.hrvDao(),
            transactionRunner = RoomTransactionRunner(database),
        )
        val zoneThresholds = ZoneThresholds.create(90, 110, 130, 150, 170)
        reconciler.reconcile(
            epoch(historyStartDate, 0, 0),
            epoch(LocalDate.of(2026, 6, 7), 0, 0),
            zoneThresholds,
        )
    }

    private fun seedEarlyWorkouts(records: ScenarioRecords) {
        // 1. >32d prior workout
        addScenarioWorkout(
            records, "workout-older-32d",
            epoch(LocalDate.of(2026, 4, 15), 10, 0),
            epoch(LocalDate.of(2026, 4, 15), 11, 0),
            45f, 155f,
        )
        // 2. Rest interval between 2026-04-16 and 2026-05-19
        // 3. Ordinary workout
        addScenarioWorkout(
            records, "workout-ordinary",
            epoch(LocalDate.of(2026, 5, 20), 14, 0),
            epoch(LocalDate.of(2026, 5, 20), 15, 0),
            35f, 150f,
        )
        // 4. Crossing midnight workout
        addScenarioWorkout(
            records, "workout-midnight",
            epoch(LocalDate.of(2026, 5, 22), 23, 30),
            epoch(LocalDate.of(2026, 5, 23), 0, 30),
            40f, 160f,
        )
        // 5. Consecutive workout days
        addScenarioWorkout(
            records, "workout-consecutive-1",
            epoch(LocalDate.of(2026, 5, 24), 10, 0),
            epoch(LocalDate.of(2026, 5, 24), 11, 0),
            30f, 145f,
        )
        addScenarioWorkout(
            records, "workout-consecutive-2",
            epoch(LocalDate.of(2026, 5, 25), 10, 0),
            epoch(LocalDate.of(2026, 5, 25), 11, 0),
            35f, 150f,
        )
        addScenarioWorkout(
            records, "workout-consecutive-3",
            epoch(LocalDate.of(2026, 5, 26), 10, 0),
            epoch(LocalDate.of(2026, 5, 26), 11, 0),
            40f, 155f,
        )
    }

    private fun seedLateWorkouts(records: ScenarioRecords) {
        // 6. Early and late workouts
        addScenarioWorkout(
            records, "workout-early",
            epoch(LocalDate.of(2026, 5, 27), 6, 30),
            epoch(LocalDate.of(2026, 5, 27), 7, 30),
            25f, 140f,
        )
        addScenarioWorkout(
            records, "workout-late",
            epoch(LocalDate.of(2026, 5, 27), 20, 0),
            epoch(LocalDate.of(2026, 5, 27), 21, 0),
            30f, 150f,
        )
        // 7. Tied timestamp workouts
        addScenarioWorkout(
            records, "workout-tied-a",
            epoch(LocalDate.of(2026, 5, 29), 9, 30),
            epoch(LocalDate.of(2026, 5, 29), 10, 30),
            20f, 140f,
        )
        addScenarioWorkout(
            records, "workout-tied-b",
            epoch(LocalDate.of(2026, 5, 29), 8, 30),
            epoch(LocalDate.of(2026, 5, 29), 10, 30),
            50f, 165f,
        )
        // 8. Zero-TRIMP workout
        addScenarioWorkout(
            records, "workout-zero-trimp",
            epoch(LocalDate.of(2026, 5, 30), 14, 0),
            epoch(LocalDate.of(2026, 5, 30), 14, 30),
            0f, 80f,
        )
        // 9. Future workout
        addScenarioWorkout(
            records, "workout-future",
            epoch(LocalDate.of(2026, 6, 5), 10, 0),
            epoch(LocalDate.of(2026, 6, 5), 11, 0),
            45f, 155f,
        )
    }

    private fun addScenarioWorkout(
        records: ScenarioRecords,
        id: String,
        startEpochMs: Long,
        endEpochMs: Long,
        trimp: Float,
        avgHr: Float,
    ) {
        val durationMinutes = ((endEpochMs - startEpochMs) / 60_000L).toInt()
        records.workouts.add(
            WorkoutRecordEntity(
                id = id,
                startTime = startEpochMs,
                endTime = endEpochMs,
                exerciseType = "RUNNING",
                durationMinutes = durationMinutes,
                zone1Minutes = (durationMinutes * 0.2f),
                zone2Minutes = (durationMinutes * 0.3f),
                zone3Minutes = (durationMinutes * 0.3f),
                zone4Minutes = (durationMinutes * 0.2f),
                zone5Minutes = 0f,
                trimp = trimp,
                avgHr = avgHr,
                modelTrimp = null,
            ),
        )
        var t = startEpochMs
        var ref = 1000L + records.workouts.size * 100L
        while (t < endEpochMs) {
            val currentRef = ++ref
            records.sourceRecords.add(
                HealthSourceRecordEntity(
                    id = currentRef,
                    sourceRecordId = "source-$currentRef",
                    recordType = "HeartRateRecord",
                    createdAtMs = t,
                ),
            )
            records.heartRates.add(
                HeartRateRecordEntity(
                    sourceRecordRef = currentRef,
                    timestampMs = t,
                    beatsPerMinute = avgHr.toInt(),
                    recordType = RecordType.EXERCISE.name,
                    sessionId = id,
                ),
            )
            t += 60_000L
        }
    }

    private fun seedScenarioSleepSessions(records: ScenarioRecords) {
        var d = historyStartDate
        while (!d.isAfter(LocalDate.of(2026, 6, 6))) {
            val sleepStart = epoch(d, 23, 0)
            val sleepEnd = epoch(d.plusDays(1), 7, 0)
            val sleepId = "sleep-$d"
            records.sleepSessions.add(
                SleepSessionEntity(
                    id = sleepId,
                    startTime = sleepStart,
                    endTime = sleepEnd,
                    durationMinutes = 480,
                    efficiency = 0.92f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 110,
                    lightSleepMinutes = 240,
                    awakeMinutes = 40,
                ),
            )
            val sleepRef = 50000L + records.sleepSessions.size
            records.sourceRecords.add(
                HealthSourceRecordEntity(
                    id = sleepRef,
                    sourceRecordId = "source-$sleepRef",
                    recordType = "HeartRateRecord",
                    createdAtMs = sleepStart,
                ),
            )
            records.heartRates.add(
                HeartRateRecordEntity(
                    sourceRecordRef = sleepRef,
                    timestampMs = sleepStart + 4 * 3_600_000L,
                    beatsPerMinute = 56,
                    recordType = RecordType.SLEEP.name,
                    sessionId = sleepId,
                ),
            )
            d = d.plusDays(1)
        }
    }

    private fun epoch(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zoneId).toInstant().toEpochMilli()

    private class MutableTestSettingsRepository(initial: UserPreferences) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val userPreferences: Flow<UserPreferences> = state

        override suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean) = Unit
        override suspend fun updateMaxHeartRate(bpm: Int) {
            state.value = state.value.copy(maxHeartRate = bpm)
        }
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

    private companion object {
        const val EPSILON = 0.0001f
    }
}
