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
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.data.preferences.appliedTrainingReadinessConfig
import app.readylytics.health.core.model.domain.heartrate.ZoneThresholds
import app.readylytics.health.core.model.domain.model.RecordType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardContexts
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.scoring.domain.cardio.UthVo2MaxCalculator
import app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver
import app.readylytics.health.core.scoring.domain.scoring.AssembleDailySummaryUseCase
import app.readylytics.health.core.scoring.domain.scoring.AssembleEverydayLoadInputUseCase
import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.BuildLoadSeriesUseCase
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeResidualFatigueUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeSleepMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeTrainingReadinessUseCase
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
        db = newInMemoryDatabase()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newInMemoryDatabase(): HealthDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HealthDatabase::class.java,
        ).allowMainThreadQueries().build()

    @Test
    fun `all reconstruction paths produce identical residualFatigue across evaluation range`() =
        runTest {
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(db, seedEverydayAndHrv = true)
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
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(db, seedEverydayAndHrv = true)
            val basePrefs = basePreferences()
            // Backfill canonical modelTrimp across the whole history first: a partial walk over
            // never-backfilled rows deliberately yields null (unknown), not a low value (HIGH-2).
            executeWalkForward(db, historyStartDate, evalEndDate, basePrefs)
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

            assertMaxHeartRateAffectsFreshBaseline(basePrefs)

            val customPrefs = basePrefs.copy(
                physiologyProfile = PhysiologyProfile.ATHLETE,
                residualFatigueHalfLifeHours = 36f,
                residualFatigueGain = 1.8f,
            )
            val profileChangedPrefs = customPrefs.copy(physiologyProfile = PhysiologyProfile.ACTIVE)
            assertEquals(36f, profileChangedPrefs.residualFatigueHalfLifeHours)
            assertEquals(1.8f, profileChangedPrefs.residualFatigueGain)
        }

    /**
     * maxHeartRate only feeds the frozen per-day hrMax baseline snapshot
     * ([app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase]): once a
     * day's baseline is frozen it is immutable and ignores later maxHeartRate changes by design,
     * so re-walking an already-frozen database can never show divergence. Exercise this on two
     * never-before-computed databases instead, so each calibrates maxHeartRate into its frozen
     * snapshot for the first time.
     */
    private suspend fun assertMaxHeartRateAffectsFreshBaseline(basePrefs: UserPreferences) {
        val hrPrefs = basePrefs.copy(maxHeartRate = 175)
        val freshBaselineDb = newInMemoryDatabase()
        val freshHrDb = newInMemoryDatabase()
        try {
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(freshBaselineDb, seedEverydayAndHrv = true)
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(freshHrDb, seedEverydayAndHrv = true)

            val freshBaselineFatigues =
                executeWalkForward(freshBaselineDb, historyStartDate, evalEndDate, basePrefs)
                    .mapNotNull { it.residualFatigue }
            val freshHrFatigues =
                executeWalkForward(freshHrDb, historyStartDate, evalEndDate, hrPrefs)
                    .mapNotNull { it.residualFatigue }
            assertNotEquals(freshBaselineFatigues, freshHrFatigues)
        } finally {
            freshBaselineDb.close()
            freshHrDb.close()
        }
    }

    @Test
    fun `residualFatigue is identical across all LoadSourceMode combinations`() =
        runTest {
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(db, seedEverydayAndHrv = true)
            val basePrefs = basePreferences()
            // Backfill canonical modelTrimp across the whole history first: a partial walk over
            // never-backfilled rows deliberately yields null (unknown), not a low value (HIGH-2).
            executeWalkForward(db, historyStartDate, evalEndDate, basePrefs)

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
    fun `normal daily scoring leaves legacy readiness unchanged and persists both training variants`() =
        runTest {
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(db, seedEverydayAndHrv = true)
            val basePrefs = basePreferences()
            // Backfill canonical modelTrimp across the whole history first: a partial walk over
            // never-backfilled rows deliberately yields null (unknown), not a low value (HIGH-2).
            executeWalkForward(db, historyStartDate, evalEndDate, basePrefs)

            val defPrefs = basePrefs.copy(
                residualFatigueHalfLifeHours = 24f,
                residualFatigueGain = 1.0f,
            )
            val minPrefs = basePrefs.copy(
                residualFatigueHalfLifeHours = SettingsDefaults.MIN_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
                // Minimum *configurable* gain, not an illegal one: ResidualFatigueConfig.clamped
                // would coerce anything below this, making the case indistinguishable from MIN.
                residualFatigueGain = SettingsDefaults.MIN_RESIDUAL_FATIGUE_GAIN,
            )
            val maxPrefs = basePrefs.copy(
                residualFatigueHalfLifeHours = SettingsDefaults.MAX_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
                residualFatigueGain = SettingsDefaults.MAX_RESIDUAL_FATIGUE_GAIN,
            )

            val defaultSummaries = executeWalkForward(db, evalStartDate, evalEndDate, defPrefs)
            val minSummaries = executeWalkForward(db, evalStartDate, evalEndDate, minPrefs)
            val maxSummaries = executeWalkForward(db, evalStartDate, evalEndDate, maxPrefs)

            assertEquals(defaultSummaries.size, minSummaries.size)
            assertEquals(defaultSummaries.size, maxSummaries.size)

            for (i in defaultSummaries.indices) {
                val def = defaultSummaries[i]
                val min = minSummaries[i]
                val max = maxSummaries[i]

                assertEquals(def.loadScoreWorkoutOnly, min.loadScoreWorkoutOnly)
                assertEquals(def.loadScoreWorkoutOnly, max.loadScoreWorkoutOnly)

                assertEquals(def.loadScoreEverydayHr, min.loadScoreEverydayHr)
                assertEquals(def.loadScoreEverydayHr, max.loadScoreEverydayHr)

                assertEquals(def.readinessWorkoutOnly, min.readinessWorkoutOnly)
                assertEquals(def.readinessWorkoutOnly, max.readinessWorkoutOnly)

                assertEquals(def.readinessEverydayHr, min.readinessEverydayHr)
                assertEquals(def.readinessEverydayHr, max.readinessEverydayHr)

                assertNotNull(def.acuteLoadRecovery)
                if (def.loadScoreWorkoutOnly != null) assertNotNull(def.trainingLoadReadinessWorkoutOnly)
                if (def.loadScoreEverydayHr != null) assertNotNull(def.trainingLoadReadinessEverydayHr)
                if (def.readinessWorkoutOnly != null) assertNotNull(def.trainingReadinessWorkoutOnly)
                if (def.readinessEverydayHr != null) assertNotNull(def.trainingReadinessEverydayHr)

                assertEquals(def.sleepScore, min.sleepScore)
                assertEquals(def.sleepDurationMinutes, min.sleepDurationMinutes)
                assertEquals(def.restingHeartRate, min.restingHeartRate)
                assertEquals(def.recoveryFlags, min.recoveryFlags)
                assertEquals(def.contributorsEmbedded, min.contributorsEmbedded)
                assertEquals(def.diagnosticsEmbedded, min.diagnosticsEmbedded)

                assertEquals(def.atlWorkoutOnly, min.atlWorkoutOnly)
                assertEquals(def.ctlWorkoutOnly, min.ctlWorkoutOnly)
                assertEquals(def.strainRatioWorkoutOnly, min.strainRatioWorkoutOnly)

                assertNotNull(def.residualFatigue, "Default fatigue must produce non-null")
                assertNotNull(min.residualFatigue, "Minimum-config fatigue must produce non-null")
                assertNotNull(max.residualFatigue, "Maximum-config fatigue must produce non-null")
            }
        }

    @Test
    fun `partial walk on never-backfilled history persists null instead of a silently low value`() =
        runTest {
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(db, seedEverydayAndHrv = true)
            val basePrefs = basePreferences()

            // No prior full walk backfills modelTrimp for workout-older-32d (2026-04-15), which
            // sits before evalStartDate but after historyStartDate. The seed for evalStartDate is
            // therefore incomplete.
            val partialSummaries = executeWalkForward(db, evalStartDate, evalEndDate, basePrefs)

            for (summary in partialSummaries) {
                assertNull(
                    summary.residualFatigue,
                    "Partial walk over never-backfilled history must persist null (unknown), " +
                        "not a low value that silently omits historical impulses",
                )
                assertNull(summary.acuteLoadRecovery)
                assertEquals(summary.loadScoreWorkoutOnly, summary.trainingLoadReadinessWorkoutOnly)
                assertEquals(summary.loadScoreEverydayHr, summary.trainingLoadReadinessEverydayHr)
                assertEquals(summary.readinessWorkoutOnly, summary.trainingReadinessWorkoutOnly)
                assertEquals(summary.readinessEverydayHr, summary.trainingReadinessEverydayHr)
            }
        }

    @Test
    fun `everyday source changes only corresponding load branch while fatigue stays identical`() =
        runTest {
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(db, seedEverydayAndHrv = true)

            val basePrefs = basePreferences()
            executeWalkForward(db, historyStartDate, evalEndDate, basePrefs)

            val workoutSummaries =
                executeWalkForward(
                    db,
                    evalStartDate,
                    evalEndDate,
                    basePrefs.copy(strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY),
                )
            val everydaySummaries =
                executeWalkForward(
                    db,
                    evalStartDate,
                    evalEndDate,
                    basePrefs.copy(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE),
                )

            val workoutSelected = workoutSummaries.last()
            val everydaySelected = everydaySummaries.last()

            assertEquals(workoutSelected.residualFatigue, everydaySelected.residualFatigue)

            val validWorkout = workoutSummaries.last()
            val validEveryday = everydaySummaries.last()

            assertEquals(
                validWorkout.trainingReadinessWorkoutOnly,
                validEveryday.trainingReadinessWorkoutOnly,
            )
            assertEquals(
                validWorkout.trainingReadinessEverydayHr,
                validEveryday.trainingReadinessEverydayHr,
            )

            assertNotEquals(
                validWorkout.trainingReadinessWorkoutOnly,
                validWorkout.trainingReadinessEverydayHr,
            )
        }

    @Test
    fun `full walk backfills the seed and every day reconstructs a non-null residualFatigue`() =
        runTest {
            ResidualFatigueScenarioSeeder(zoneId, historyStartDate)
                .seedDeterministicScenario(db, seedEverydayAndHrv = true)
            val basePrefs = basePreferences()

            val fullSummaries = executeWalkForward(db, historyStartDate, evalEndDate, basePrefs)

            val nonNullCount = fullSummaries.count { it.residualFatigue != null }
            assertEquals(
                fullSummaries.size,
                nonNullCount,
                "Every day must reconstruct a non-null residualFatigue once the seed is fully backfilled",
            )
        }

    private fun basePreferences(): UserPreferences =
        UserPreferences(
            scoringZoneId = zoneId.id,
            installDate = historyStartDate.minusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
            maxHeartRate = 190,
            autoCalculateMaxHr = false,
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
            val contexts =
                WalkForwardContexts(
                    trimp = support.buildWalkForwardTrimpContext(startDate, endDate, zoneId),
                    baseline = support.buildWalkForwardBaselineContext(startDate, endDate, zoneId),
                    fatigue = support.buildWalkForwardFatigueContext(startDate, endDate, zoneId),
                )
            var current = startDate
            while (!current.isAfter(endDate)) {
                support.recomputeDay(current, null, prefs, contexts)
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
            database.vo2MaxRecordDao(),
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
            loaders = ScoringDataLoaders(
                dataLoader,
                bodyMetricsDataLoader,
                seriesLoader,
            ),
            settingsRepo = settingsRepo,
            baselineComputer = baselineComputer,
            scoringConfigFactory = scoringConfigFactory,
            useCases = ScoringDayUseCases(
                ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
                ComputeResidualFatigueUseCase(),
                ResolveDailyBaselinesUseCase(baselineComputer),
                AssembleEverydayLoadInputUseCase(),
                        ComputeTrainingReadinessUseCase(scoringCalculator),
                UthVo2MaxCalculator(),
                Vo2MaxSourceResolver(),
            ),
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
        override suspend fun updateTrainingReadinessConfig(
            config: app.readylytics.health.core.model.domain.scoring.TrainingReadinessConfig
        ) = Unit
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
