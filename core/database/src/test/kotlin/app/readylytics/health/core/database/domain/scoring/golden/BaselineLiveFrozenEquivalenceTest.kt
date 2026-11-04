package app.readylytics.health.core.database.domain.scoring.golden

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UserPreferences
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class BaselineLiveFrozenEquivalenceTest {
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin")
    private val targetDate: LocalDate = LocalDate.of(2026, 6, 15)

    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `scoring day live then frozen produces bit-identical scores and flags`() =
        runTest {
            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    physiologyProfile = PhysiologyProfile.ACTIVE,
                    installDate = targetDate.minusDays(60).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                )
            val settingsRepo = FakeSettingsRepository(prefs)
            val scoringRepo = GoldenScoringRepositoryFactory(db).create(settingsRepo)
            val fixtures = GoldenSleepFixtures(db, targetDate, zoneId)

            // Seed 14 days of calibrated sleep history
            fixtures.seedCalibratedHistory(days = 14)

            // Seed tonight's sleep session on targetDate
            val sessionDate = targetDate.minusDays(1)
            val sleepStart = sessionDate.atTime(23, 0).atZone(zoneId).toInstant().toEpochMilli()
            val sleepEnd = targetDate.atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli()
            val tonightSessionId = "tonight_sleep"

            val tonightSession =
                SleepSessionEntity(
                    id = tonightSessionId,
                    startTime = sleepStart,
                    endTime = sleepEnd,
                    durationMinutes = 480,
                    efficiency = 92f,
                    deepSleepMinutes = 95,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 265,
                    awakeMinutes = 30,
                    deviceName = "Pixel",
                )
            db.sleepSessionDao().upsertAll(listOf(tonightSession))
            fixtures.seedMidnightSleepStages(tonightSessionId, sleepStart, sleepEnd)
            fixtures.seedMidnightSleepHr(tonightSessionId, sleepStart)

            // Pass 1: Live scoring pass (unfrozen)
            val liveSummary = checkNotNull(scoringRepo.computeDailySummary(targetDate))
            assertNotNull(liveSummary.zRhr)
            assertNotNull(liveSummary.zLnHrv)

            // Persist the summary with baselineCalculatedAtDate set (freezing it)
            val entity = DailySummaryMapper.toEntity(liveSummary, zoneId)
            db.dailySummaryDao().upsert(entity)

            // Pass 2: Frozen scoring pass
            val frozenSummary = checkNotNull(scoringRepo.computeDailySummary(targetDate))

            // Assert Bit-Identical Equivalence
            assertEquals("zRhr must be bit-identical", liveSummary.zRhr, frozenSummary.zRhr)
            assertEquals("zLnHrv must be bit-identical", liveSummary.zLnHrv, frozenSummary.zLnHrv)
            assertEquals(
                "restingHrRatio must be bit-identical",
                liveSummary.restingHrRatio,
                frozenSummary.restingHrRatio,
            )
            assertEquals("sRest must be bit-identical", liveSummary.sRest, frozenSummary.sRest)
            assertEquals("sleepScore must be bit-identical", liveSummary.sleepScore, frozenSummary.sleepScore)
            assertEquals(
                "readiness must be bit-identical",
                liveSummary.readinessWorkoutOnly,
                frozenSummary.readinessWorkoutOnly,
            )
            assertEquals("recoveryFlags must match", liveSummary.recoveryFlags, frozenSummary.recoveryFlags)
            assertEquals("rhrBpm must match", liveSummary.rhrBpm, frozenSummary.rhrBpm)
            assertEquals("rhrSigma must match", liveSummary.rhrSigma, frozenSummary.rhrSigma)
            assertEquals("hrvMuMssd must match", liveSummary.hrvMuMssd, frozenSummary.hrvMuMssd)
            assertEquals("hrvSigmaMssd must match", liveSummary.hrvSigmaMssd, frozenSummary.hrvSigmaMssd)
        }

    @Test
    fun `calibrated day with no historical RHR nadirs in 30-day window scores identically live and frozen`() =
        runTest {
            val prefs =
                UserPreferences(
                    scoringZoneId = zoneId.id,
                    physiologyProfile = PhysiologyProfile.ACTIVE,
                    installDate = targetDate.minusDays(90).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                )
            val settingsRepo = FakeSettingsRepository(prefs)
            val scoringRepo = GoldenScoringRepositoryFactory(db).create(settingsRepo)
            // Seed 10 calibrated nights >30 days ago (so user is calibrated, but 30-day RHR window is empty)
            val oldAnchor = targetDate.minusDays(40)
            val oldFixtures = GoldenSleepFixtures(db, oldAnchor, zoneId)
            oldFixtures.seedCalibratedHistory(days = 10)

            // Seed tonight's sleep session on targetDate
            val fixtures = GoldenSleepFixtures(db, targetDate, zoneId)
            val sessionDate = targetDate.minusDays(1)
            val sleepStart = sessionDate.atTime(23, 0).atZone(zoneId).toInstant().toEpochMilli()
            val sleepEnd = targetDate.atTime(7, 0).atZone(zoneId).toInstant().toEpochMilli()
            val tonightSessionId = "tonight_sleep_empty_rhr_window"

            val tonightSession =
                SleepSessionEntity(
                    id = tonightSessionId,
                    startTime = sleepStart,
                    endTime = sleepEnd,
                    durationMinutes = 480,
                    efficiency = 92f,
                    deepSleepMinutes = 95,
                    remSleepMinutes = 90,
                    lightSleepMinutes = 265,
                    awakeMinutes = 30,
                    deviceName = "Pixel",
                )
            db.sleepSessionDao().upsertAll(listOf(tonightSession))
            fixtures.seedMidnightSleepStages(tonightSessionId, sleepStart, sleepEnd)
            fixtures.seedMidnightSleepHr(tonightSessionId, sleepStart)

            // Pass 1: Live scoring pass (unfrozen)
            val liveSummary = checkNotNull(scoringRepo.computeDailySummary(targetDate))
            assertNull("zRhr must be null when 30-day RHR window is empty", liveSummary.zRhr)
            assertNull("rhrBpm must not freeze DEFAULT_RHR_BPM when 30-day RHR window is empty", liveSummary.rhrBpm)

            // Persist the summary with baselineCalculatedAtDate set (freezing it)
            val entity = DailySummaryMapper.toEntity(liveSummary, zoneId)
            db.dailySummaryDao().upsert(entity)

            // Pass 2: Frozen scoring pass
            val frozenSummary = checkNotNull(scoringRepo.computeDailySummary(targetDate))

            assertEquals("zRhr must remain null on frozen replay", liveSummary.zRhr, frozenSummary.zRhr)
            assertEquals("zLnHrv must be bit-identical", liveSummary.zLnHrv, frozenSummary.zLnHrv)
            assertEquals("sRest must be bit-identical", liveSummary.sRest, frozenSummary.sRest)
            assertEquals("sleepScore must be bit-identical", liveSummary.sleepScore, frozenSummary.sleepScore)
            assertEquals(
                "readiness must be bit-identical",
                liveSummary.readinessWorkoutOnly,
                frozenSummary.readinessWorkoutOnly,
            )
            assertEquals("recoveryFlags must match", liveSummary.recoveryFlags, frozenSummary.recoveryFlags)
            assertEquals("rhrBpm must match", liveSummary.rhrBpm, frozenSummary.rhrBpm)
        }
}
