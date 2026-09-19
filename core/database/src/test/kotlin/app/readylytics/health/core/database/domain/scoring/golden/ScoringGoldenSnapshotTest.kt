package app.readylytics.health.core.database.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.database.data.repository.ScoringRepositoryImpl
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.scoring.domain.scoring.WorkoutInputRevision
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private val targetDate: LocalDate = LocalDate.of(2026, 6, 15)
    private val targetMidnightMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()

    private lateinit var db: HealthDatabase
    private lateinit var settingsRepo: FakeSettingsRepository
    private lateinit var repo: ScoringRepositoryImpl
    private lateinit var sleepFixtures: GoldenSleepFixtures

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        sleepFixtures = GoldenSleepFixtures(db, targetDate, zoneId)

        val defaultPrefs =
            UserPreferences(
                scoringZoneId = zoneId.id,
                installDate =
                    targetDate
                        .minusDays(60)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                physiologyProfile = PhysiologyProfile.ACTIVE,
                maxHeartRate = 190,
                age = 32,
                goalSleepHours = 8f,
                rasScalingFactor = 0.2f,
            )
        settingsRepo = FakeSettingsRepository(defaultPrefs)
        repo = GoldenScoringRepositoryFactory(db).create(settingsRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `case 1 - day with workouts and frozen snapshot`() =
        runTest {
            val caseName = "day_with_workouts_and_frozen_snapshot"
            sleepFixtures.seedCalibratedHistory()

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
            sleepFixtures.seedCalibratedHistory()

            val sleepStart =
                targetDate
                    .minusDays(1)
                    .atTime(22, 30)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val sleepEnd =
                targetDate
                    .atTime(6, 45)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
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
            sleepFixtures.seedMidnightSleepStages(sessionId, sleepStart, sleepEnd)

            sleepFixtures.seedMidnightSleepHr(sessionId, sleepStart)

            assertMatchesGolden(caseName)
        }

    @Test
    fun `case 3 - day with no sleep session`() =
        runTest {
            val caseName = "day_with_no_sleep_session"
            sleepFixtures.seedCalibratedHistory()

            val prefs = settingsRepo.userPreferences.first()
            val hrMax = HeartRateFormulas.resolveMaxHeartRate(prefs)
            val expectedSnapshotId = HistoricalRunIdentity.computeSnapshotId(prefs, hrMax)
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
                    modelTrimp = 0.0f,
                    modelTrimpQuality = WorkoutHrQuality.RAW.name,
                    // This fixture supplies a validated prior with matching current input identity.
                    modelTrimpSourceRevision =
                        WorkoutInputRevision.compute(
                            "workout_case3",
                            targetMidnightMs + 8 * 3600_000L,
                            targetMidnightMs + 9 * 3600_000L,
                            "CYCLING",
                            null,
                            emptyList(),
                        ),
                    modelTrimpSnapshotId = expectedSnapshotId,
                    modelTrimpAlgorithmRevision = SettingsDefaults.CURRENT_SCORING_VERSION,
                )
            db.workoutDao().upsertAll(listOf(workout))

            assertMatchesGolden(caseName)
        }

    @Test
    fun `case 4 - day with early return uncalibrated`() =
        runTest {
            val caseName = "day_with_early_return_uncalibrated"
            // Only 2 historical days (< 7 MIN_SESSIONS_FOR_CALIBRATION)
            sleepFixtures.seedCalibratedHistory(days = 2)

            val sleepStart =
                targetDate
                    .minusDays(1)
                    .atTime(23, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val sleepEnd =
                targetDate
                    .atTime(7, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
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
            sleepFixtures.seedCalibratedHistory()

            val customPrefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    installDate =
                        targetDate
                            .minusDays(60)
                            .atStartOfDay(zoneId)
                            .toInstant()
                            .toEpochMilli(),
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    maxHeartRate = 198,
                    age = 28,
                    goalSleepHours = 8.5f,
                    rasScalingFactor = 0.18f,
                )
            settingsRepo = FakeSettingsRepository(customPrefs)
            repo = GoldenScoringRepositoryFactory(db).create(settingsRepo)

            val sleepStart =
                targetDate
                    .minusDays(1)
                    .atTime(23, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val sleepEnd =
                targetDate
                    .atTime(7, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
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
            sleepFixtures.seedCalibratedHistory()

            val coreStart =
                targetDate
                    .minusDays(1)
                    .atTime(23, 30)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val coreEnd =
                targetDate
                    .atTime(6, 30)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
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
            val napStart =
                targetDate
                    .atTime(13, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
            val napEnd =
                targetDate
                    .atTime(14, 0)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli()
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
        val actualJson = GoldenEntityJson.encode(actualEntity)

        if (UPDATE_GOLDEN) {
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

    private companion object {
        /**
         * Regeneration is opt-in via `-Dupdate.golden=true` (forwarded to the test JVM by
         * `core/database/build.gradle.kts`). It must never be hardcoded to `true`: a suite that
         * always rewrites its own fixture and returns before asserting can never fail, which
         * silently disables the scoring-regression lock these fixtures exist to provide.
         */
        val UPDATE_GOLDEN: Boolean = System.getProperty("update.golden") == "true"
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
