package app.readylytics.health.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepStageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.domain.preferences.PhysiologyProfile
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.core.database.data.repository.ReadinessSummaryCoordinator
import app.readylytics.health.core.database.data.repository.ScoringDayDataLoader
import app.readylytics.health.core.database.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.database.data.repository.SleepSessionRepositoryImpl
import app.readylytics.health.domain.preferences.SettingsRepository
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
import app.readylytics.health.core.scoring.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.core.scoring.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.core.scoring.domain.scoring.strategies.SleepScoringStrategy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ScoringGoldenSnapshotTest {
    private val json = Json { prettyPrint = true }
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private val targetDate: LocalDate = LocalDate.of(2026, 6, 15)
    private val targetMidnightMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

    private lateinit var db: HealthDatabase
    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var repo: ScoringRepositoryImpl

    private fun createRepository(settingsRepository: SettingsRepository): ScoringRepositoryImpl {
        val scoringHistoryRepository =
            ScoringHistoryRepositoryImpl(
                db.heartRateDao(),
                db.hrvDao(),
                db.sleepSessionDao(),
                db.dailySummaryDao(),
                db.minuteBucketDao(),
            )
        val loadScoringStrategy = LoadScoringStrategy()
        val scoringCalculator =
            CompositeScoringCalculator(
                sleepStrategy = SleepScoringStrategy(loadScoringStrategy),
                rasStrategy = RasScoringStrategy(),
                loadStrategy = loadScoringStrategy,
            )
        val baselineComputer = BaselineComputer(scoringHistoryRepository, scoringCalculator)
        val scoringConfigFactory = ScoringConfigFactory()
        val sleepSessionRepository = SleepSessionRepositoryImpl(db.sleepSessionDao(), db.sleepStageDao())
        val settingsRepo = FakeSettingsRepository(UserPreferences())
        val circadianConsistencyRepository =
            CircadianConsistencyRepository(sleepSessionRepository, settingsRepo, FakeEncryptionManager())
        val sleepModifierResolver = SleepModifierResolver(sleepSessionRepository, circadianConsistencyRepository)
        val computeSleepMetricsUseCase =
            ComputeSleepMetricsUseCase(
                baselineComputer = baselineComputer,
                scoringHistoryRepository = scoringHistoryRepository,
                scoringCalculator = scoringCalculator,
                scoringConfigFactory = scoringConfigFactory,
                encryptionManager = FakeEncryptionManager(),
                hrvResolver = CurrentNightHrvResolver(scoringHistoryRepository),
                sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(scoringHistoryRepository),
                nadirAnalyzer = SleepNadirAnalyzer(scoringCalculator),
                coverageValidator = HrCoverageValidator(),
                sleepModifierResolver = sleepModifierResolver,
            )

        val dataLoader = ScoringDayDataLoader(
            db.workoutDao(), db.sleepSessionDao(), db.dailySummaryDao(), db.heartRateDao(),
            db.minuteBucketDao(), db.weightRecordDao(), db.bodyFatRecordDao(),
            db.bloodPressureRecordDao(), db.oxygenSaturationRecordDao(),
            db.bodyTemperatureRecordDao(),
        )
        val buildLoadSeriesUseCase = BuildLoadSeriesUseCase(scoringCalculator)
        val resolveDailyBaselinesUseCase = ResolveDailyBaselinesUseCase(baselineComputer)
        val assembleDailySummaryUseCase = AssembleDailySummaryUseCase()
        val readinessSummaryCoordinator =
            ReadinessSummaryCoordinator(
                dataLoader = dataLoader,
                scoringHistoryRepository = scoringHistoryRepository,
                baselineComputer = baselineComputer,
                buildLoadSeriesUseCase = buildLoadSeriesUseCase,
                computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                resolveDailyBaselinesUseCase = resolveDailyBaselinesUseCase,
                assembleDailySummaryUseCase = assembleDailySummaryUseCase,
            )

        return ScoringRepositoryImpl(
            dataLoader = dataLoader,
            settingsRepo = settingsRepository,
            baselineComputer = baselineComputer,
            scoringConfigFactory = scoringConfigFactory,
            computeDailyTrimpUseCase = ComputeDailyTrimpUseCase(ComputeWorkoutTrimpUseCase()),
            resolveDailyBaselinesUseCase = resolveDailyBaselinesUseCase,
            assembleEverydayLoadInputUseCase = AssembleEverydayLoadInputUseCase(),
            scoringHistoryRepository = scoringHistoryRepository,
            readinessSummaryCoordinator = readinessSummaryCoordinator,
            defaultDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val defaultPrefs =
            UserPreferences(
                scoringZoneId = zoneId.id,
                installDate = targetDate.minusDays(60).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                physiologyProfile = PhysiologyProfile.ACTIVE,
                maxHeartRate = 190,
                age = 32,
                goalSleepHours = 8f,
                rasScalingFactor = 0.2f,
            )
        settingsRepo = FakeSettingsRepository(defaultPrefs)
        repo = createRepository(settingsRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedCalibratedHistory(days: Int = 14) {
        val sleepSessions = mutableListOf<SleepSessionEntity>()
        val hrSamples = mutableListOf<HeartRateRecordEntity>()
        val hrvSamples = mutableListOf<HrvRecordEntity>()
        val sourceRecords = mutableListOf<HealthSourceRecordEntity>()

        for (i in 1..days) {
            val date = targetDate.minusDays(i.toLong())
            val startMs = date.atTime(23, 0).atZone(zoneId).toInstant().toEpochMilli()
            val endMs = date.plusDays(1).atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli()
            val sessionId = "hist_sleep_$i"
            val sourceRef = i.toLong()

            sourceRecords +=
                HealthSourceRecordEntity(
                    id = sourceRef,
                    sourceRecordId = "hist_source_$i",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            sleepSessions +=
                SleepSessionEntity(
                    id = sessionId,
                    startTime = startMs,
                    endTime = endMs,
                    durationMinutes = 480,
                    efficiency = 90f,
                    deepSleepMinutes = 90,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 270,
                    awakeMinutes = 30,
                    deviceName = "Pixel",
                )
            for (step in 0..48) {
                hrSamples +=
                    HeartRateRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = startMs + step * 10 * 60_000L,
                        beatsPerMinute = 52 + (step % 6),
                        recordType = "SLEEP",
                        sessionId = sessionId,
                        deviceName = "Pixel",
                    )
            }
            hrvSamples +=
                HrvRecordEntity(
                    sourceRecordRef = sourceRef,
                    timestampMs = startMs + 3600_000L,
                    rmssdMs = 55f + (i % 5),
                    recordType = "SLEEP",
                    sessionId = sessionId,
                    deviceName = "Pixel",
                )
        }
        db.sourceRecordDao().insertAll(sourceRecords)
        db.sleepSessionDao().upsertAll(sleepSessions)
        db.heartRateDao().upsertAll(hrSamples)
        db.hrvDao().upsertAll(hrvSamples)
    }

    @Test
    fun `case 1 - day with workouts and frozen snapshot`() =
        runTest {
            val caseName = "day_with_workouts_and_frozen_snapshot"
            seedCalibratedHistory()

            val frozenSnapshot =
                DailySummaryEntity(
                    dateMidnightMs = targetMidnightMs,
                    baselineCalculatedAtDate = targetDate,
                    hrMax = 188f,
                    rasScalingFactor = 0.22f,
                    rhrBpm = 51.5f,
                    rhrSigma = 1.8f,
                    hrvMuMssd = 3.95f,
                    hrvSigmaMssd = 0.25f,
                    baselineObservationCount = 14,
                )
            db.dailySummaryDao().upsert(frozenSnapshot)

            val workout =
                WorkoutRecordEntity(
                    id = "workout_case1",
                    startTime = targetMidnightMs + 10 * 3600_000L,
                    endTime = targetMidnightMs + 11 * 3600_000L,
                    exerciseType = "RUNNING",
                    durationMinutes = 60,
                    zone1Minutes = 10f,
                    zone2Minutes = 30f,
                    zone3Minutes = 15f,
                    zone4Minutes = 5f,
                    zone5Minutes = 0f,
                    trimp = 45f,
                    avgHr = 145f,
                )
            db.workoutDao().upsertAll(listOf(workout))

            val sourceRef = 1000L
            db.sourceRecordDao().insertAll(
                listOf(
                    HealthSourceRecordEntity(
                        id = sourceRef,
                        sourceRecordId = "case1_workout_hr",
                        recordType = "HEART_RATE",
                        createdAtMs = 0L,
                    ),
                ),
            )
            val hrSamples =
                (0..60).map { step ->
                    HeartRateRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = workout.startTime + step * 60_000L,
                        beatsPerMinute = 135 + (step % 20),
                        recordType = "EXERCISE",
                        sessionId = workout.id,
                        deviceName = "Pixel",
                    )
                }
            db.heartRateDao().upsertAll(hrSamples)

            assertMatchesGolden(caseName)
        }

    @Test
    fun `case 2 - day with sleep spanning midnight`() =
        runTest {
            val caseName = "day_with_sleep_spanning_midnight"
            seedCalibratedHistory()

            val sleepStart = targetDate.minusDays(1).atTime(22, 30).atZone(zoneId).toInstant().toEpochMilli()
            val sleepEnd = targetDate.atTime(6, 45).atZone(zoneId).toInstant().toEpochMilli()
            val sessionId = "sleep_case2"

            db.sleepSessionDao().upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = sessionId,
                        startTime = sleepStart,
                        endTime = sleepEnd,
                        durationMinutes = 495,
                        efficiency = 92f,
                        deepSleepMinutes = 105,
                        remSleepMinutes = 95,
                        lightSleepMinutes = 265,
                        awakeMinutes = 30,
                        deviceName = "Pixel",
                    ),
                ),
            )
            db.sleepStageDao().upsertAll(
                listOf(
                    SleepStageEntity(
                        sessionId = sessionId,
                        startTime = sleepStart,
                        endTime = sleepStart + 90 * 60000L,
                        stageType = "LIGHT",
                        durationMinutes = 90,
                    ),
                    SleepStageEntity(
                        sessionId = sessionId,
                        startTime = sleepStart + 90 * 60000L,
                        endTime = sleepStart + 195 * 60000L,
                        stageType = "DEEP",
                        durationMinutes = 105,
                    ),
                    SleepStageEntity(
                        sessionId = sessionId,
                        startTime = sleepStart + 195 * 60000L,
                        endTime = sleepStart + 290 * 60000L,
                        stageType = "REM",
                        durationMinutes = 95,
                    ),
                    SleepStageEntity(
                        sessionId = sessionId,
                        startTime = sleepStart + 290 * 60000L,
                        endTime = sleepEnd,
                        stageType = "LIGHT",
                        durationMinutes = 115,
                    ),
                ),
            )

            val sourceRef = 2000L
            db.sourceRecordDao().insertAll(
                listOf(
                    HealthSourceRecordEntity(
                        id = sourceRef,
                        sourceRecordId = "case2_sleep_source",
                        recordType = "HEART_RATE",
                        createdAtMs = 0L,
                    ),
                ),
            )
            val hrSamples =
                (0..49).map { step ->
                    HeartRateRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = sleepStart + step * 10 * 60_000L,
                        beatsPerMinute = 50 + (step % 5),
                        recordType = "SLEEP",
                        sessionId = sessionId,
                        deviceName = "Pixel",
                    )
                }
            db.heartRateDao().upsertAll(hrSamples)
            db.hrvDao().upsertAll(
                listOf(
                    HrvRecordEntity(
                        sourceRecordRef = sourceRef,
                        timestampMs = sleepStart + 2 * 3600_000L,
                        rmssdMs = 62f,
                        recordType = "SLEEP",
                        sessionId = sessionId,
                        deviceName = "Pixel",
                    ),
                ),
            )

            assertMatchesGolden(caseName)
        }

    @Test
    fun `case 3 - day with no sleep session`() =
        runTest {
            val caseName = "day_with_no_sleep_session"
            seedCalibratedHistory()

            val workout =
                WorkoutRecordEntity(
                    id = "workout_case3",
                    startTime = targetMidnightMs + 8 * 3600_000L,
                    endTime = targetMidnightMs + 9 * 3600_000L,
                    exerciseType = "CYCLING",
                    durationMinutes = 60,
                    zone1Minutes = 15f,
                    zone2Minutes = 35f,
                    zone3Minutes = 10f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 35f,
                    avgHr = 130f,
                )
            db.workoutDao().upsertAll(listOf(workout))

            assertMatchesGolden(caseName)
        }

    @Test
    fun `case 4 - day with early return uncalibrated`() =
        runTest {
            val caseName = "day_with_early_return_uncalibrated"
            // Only 2 historical days (< 7 MIN_SESSIONS_FOR_CALIBRATION)
            seedCalibratedHistory(days = 2)

            val sleepStart = targetDate.minusDays(1).atTime(23, 0).atZone(zoneId).toInstant().toEpochMilli()
            val sleepEnd = targetDate.atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli()
            val sessionId = "sleep_uncalib"
            db.sleepSessionDao().upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = sessionId,
                        startTime = sleepStart,
                        endTime = sleepEnd,
                        durationMinutes = 480,
                        efficiency = 88f,
                        deepSleepMinutes = 80,
                        remSleepMinutes = 80,
                        lightSleepMinutes = 280,
                        awakeMinutes = 40,
                        deviceName = "Pixel",
                    ),
                ),
            )

            assertMatchesGolden(caseName)
        }

    @Test
    fun `case 5 - day with hrmax from prefs vs snapshot`() =
        runTest {
            val caseName = "day_with_hrmax_from_prefs_vs_snapshot"
            seedCalibratedHistory()

            val customPrefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    installDate = targetDate.minusDays(60).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    maxHeartRate = 198,
                    age = 28,
                    goalSleepHours = 8.5f,
                    rasScalingFactor = 0.18f,
                )
            settingsRepo = FakeSettingsRepository(customPrefs)
            repo = createRepository(settingsRepo)

            val sleepStart = targetDate.minusDays(1).atTime(23, 0).atZone(zoneId).toInstant().toEpochMilli()
            val sleepEnd = targetDate.atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli()
            val sessionId = "sleep_case5"
            db.sleepSessionDao().upsertAll(
                listOf(
                    SleepSessionEntity(
                        id = sessionId,
                        startTime = sleepStart,
                        endTime = sleepEnd,
                        durationMinutes = 480,
                        efficiency = 91f,
                        deepSleepMinutes = 90,
                        remSleepMinutes = 90,
                        lightSleepMinutes = 270,
                        awakeMinutes = 30,
                        deviceName = "Pixel",
                    ),
                ),
            )

            assertMatchesGolden(caseName)
        }

    @Test
    fun `case 6 - day with nap and supplemental sleep`() =
        runTest {
            val caseName = "day_with_nap_and_supplemental_sleep"
            seedCalibratedHistory()

            val coreStart = targetDate.minusDays(1).atTime(23, 30).atZone(zoneId).toInstant().toEpochMilli()
            val coreEnd = targetDate.atTime(6, 30).atZone(zoneId).toInstant().toEpochMilli()
            val coreSession =
                SleepSessionEntity(
                    id = "core_sleep_case6",
                    startTime = coreStart,
                    endTime = coreEnd,
                    durationMinutes = 420,
                    efficiency = 90f,
                    deepSleepMinutes = 75,
                    remSleepMinutes = 75,
                    lightSleepMinutes = 240,
                    awakeMinutes = 30,
                    deviceName = "Pixel",
                )
            val napStart = targetDate.atTime(13, 0).atZone(zoneId).toInstant().toEpochMilli()
            val napEnd = targetDate.atTime(14, 0).atZone(zoneId).toInstant().toEpochMilli()
            val napSession =
                SleepSessionEntity(
                    id = "nap_sleep_case6",
                    startTime = napStart,
                    endTime = napEnd,
                    durationMinutes = 60,
                    efficiency = 85f,
                    deepSleepMinutes = 15,
                    remSleepMinutes = 10,
                    lightSleepMinutes = 30,
                    awakeMinutes = 5,
                    deviceName = "Pixel",
                )
            db.sleepSessionDao().upsertAll(listOf(coreSession, napSession))

            assertMatchesGolden(caseName)
        }

    private suspend fun assertMatchesGolden(caseName: String) {
        val summary = repo.computeDailySummary(targetDate)
        val actualEntity = DailySummaryMapper.toEntity(summary, zoneId)
        val actualJson = json.encodeToString(actualEntity)

        if (System.getProperty("update.golden") == "true") {
            val target = goldenWriteTarget(caseName)
            target.parentFile?.mkdirs()
            target.writeText(actualJson)
            println("Golden fixture written to ${target.absolutePath}")
            return
        }

        val expectedJson = loadGoldenJsonOrNull(caseName)
        assertNotNull(expectedJson, "Missing golden fixture for $caseName. Run with -Dupdate.golden=true to generate.")
        assertEquals(expectedJson, actualJson, "Output diverged from golden fixture $caseName")
    }

    private fun goldenResourceRelativePath(caseName: String): String = "golden/$caseName.json"

    private fun goldenFileCandidates(caseName: String): List<File> =
        listOf(
            File("src/test/resources/golden/$caseName.json"),
            File("core/database/src/test/resources/golden/$caseName.json"),
            File("../core/database/src/test/resources/golden/$caseName.json"),
        )

    private fun loadGoldenJsonOrNull(caseName: String): String? {
        javaClass.classLoader?.getResourceAsStream(goldenResourceRelativePath(caseName))?.use {
            return it.bufferedReader().readText()
        }
        return goldenFileCandidates(caseName).firstOrNull { it.exists() }?.readText()
    }

    private fun goldenWriteTarget(caseName: String): File =
        goldenFileCandidates(caseName).firstOrNull { it.parentFile?.exists() == true }
            ?: goldenFileCandidates(caseName).first()
}
