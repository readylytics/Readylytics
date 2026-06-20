package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepHrSample
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.SleepSessionEntity
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.data.security.EncryptionManager
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.DailySummaryEntity
import app.readylytics.health.domain.model.DailySummaryMapper
import app.readylytics.health.domain.scoring.sleep.CurrentNightHrvResolver
import app.readylytics.health.domain.scoring.sleep.HrCoverageValidator
import app.readylytics.health.domain.scoring.sleep.SleepNadirAnalyzer
import app.readylytics.health.domain.scoring.sleep.SleepPercentileRhrCalculator
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import app.readylytics.health.domain.scoring.strategies.RasScoringStrategy
import app.readylytics.health.domain.scoring.strategies.SleepScoringStrategy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

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
                "Rounded readinessWorkoutOnly must not flip when a live-computed day is recomputed from its frozen summary.",
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
        val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
        val settingsRepo = mockk<SettingsRepository>(relaxed = true)
        val heartRateDao = mockk<HeartRateDao>(relaxed = true)
        val hrvDao = mockk<HrvDao>(relaxed = true)
        val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
        val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
        val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
        val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)

        every { settingsRepo.userPreferences } returns flowOf(prefs)

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

        val scoringCalculator =
            CompositeScoringCalculator(
                SleepScoringStrategy(LoadScoringStrategy()),
                RasScoringStrategy(),
                LoadScoringStrategy(),
            )
        val baselineComputer =
            BaselineComputer(
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                sleepSessionDao = sleepSessionDao,
                scoringCalculator = scoringCalculator,
                dailySummaryDao = dailySummaryDao,
            )
        val scoringConfigFactory = ScoringConfigFactory()
        val encryptionManager = mockk<EncryptionManager>(relaxed = true)
        val currentNightHrvResolver = CurrentNightHrvResolver(hrvDao)
        val sleepPercentileRhrCalculator = SleepPercentileRhrCalculator(heartRateDao, sleepSessionDao)
        val sleepNadirAnalyzer = SleepNadirAnalyzer(heartRateDao, scoringCalculator)
        val coverageValidator = HrCoverageValidator()
        val computeSleepMetricsUseCase =
            ComputeSleepMetricsUseCase(
                baselineComputer = baselineComputer,
                dailySummaryDao = dailySummaryDao,
                heartRateDao = heartRateDao,
                scoringCalculator = scoringCalculator,
                scoringConfigFactory = scoringConfigFactory,
                encryptionManager = encryptionManager,
                hrvResolver = currentNightHrvResolver,
                sleepPercentileRhrCalculator = sleepPercentileRhrCalculator,
                nadirAnalyzer = sleepNadirAnalyzer,
                coverageValidator = coverageValidator,
            )

        val repo =
            ScoringRepositoryImpl(
                workoutDao = workoutDao,
                sleepSessionDao = sleepSessionDao,
                dailySummaryDao = dailySummaryDao,
                settingsRepo = settingsRepo,
                scoringCalculator = scoringCalculator,
                baselineComputer = baselineComputer,
                computeSleepMetricsUseCase = computeSleepMetricsUseCase,
                scoringConfigFactory = scoringConfigFactory,
                computeWorkoutTrimpUseCase = ComputeWorkoutTrimpUseCase(),
                heartRateDao = heartRateDao,
                hrvDao = hrvDao,
                weightRecordDao = weightRecordDao,
                bodyFatRecordDao = bodyFatRecordDao,
                bloodPressureRecordDao = bloodPressureRecordDao,
                oxygenSaturationRecordDao = oxygenSaturationRecordDao,
                sleepPercentileRhrCalculator = sleepPercentileRhrCalculator,
            )

        return ScopeResult(label = label, summary = repo.computeDailySummary(targetDate))
    }

    private fun assertMatrixPopulated(summary: DailySummary) {
        assertNotNull(summary.sleepScore, "sleepScore should be populated by the determinism fixture")
        // US-03: readiness now lives in the workout-only variant column; legacy readinessScore is frozen.
        assertNotNull(
            summary.readinessWorkoutOnly,
            "readinessWorkoutOnly should be populated by the determinism fixture",
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
        val fields =
            listOf(
                "sleepScore" to { it: DailySummary -> it.sleepScore },
                "readinessWorkoutOnly" to { it: DailySummary -> it.readinessWorkoutOnly },
                "rhrBpm" to { it: DailySummary -> it.rhrBpm },
                "rhrSigma" to { it: DailySummary -> it.rhrSigma },
                "hrvMuMssd" to { it: DailySummary -> it.hrvMuMssd },
                "hrvSigmaMssd" to { it: DailySummary -> it.hrvSigmaMssd },
                "restingHeartRate" to { it: DailySummary -> it.restingHeartRate },
                "nocturnalHrv" to { it: DailySummary -> it.nocturnalHrv },
                "baselineObservationCount" to { it: DailySummary -> it.baselineObservationCount },
                "zLnHrv" to { it: DailySummary -> it.zLnHrv },
                "zRhr" to { it: DailySummary -> it.zRhr },
                "sRest" to { it: DailySummary -> it.sRest },
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
    }

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
                        id = "$sessionId-hr-$index",
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
    )

    private data class ScopeResult(
        val label: String,
        val summary: DailySummary,
    )
}
