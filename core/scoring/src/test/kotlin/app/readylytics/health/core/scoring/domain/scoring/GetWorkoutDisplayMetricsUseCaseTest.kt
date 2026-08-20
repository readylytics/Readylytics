package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutLoadMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassifier

import app.readylytics.health.core.model.domain.scoring.ScoringConstants
import app.readylytics.health.core.model.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.scoring.domain.scoring.strategies.RasScoringStrategy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GetWorkoutDisplayMetricsUseCaseTest {
    private val dailySummaryRepository = mockk<DailySummaryRepository>()
    private val heartRateRepository = mockk<HeartRateRepository>()
    private val settingsRepo = mockk<SettingsRepository>()
    private val computeWorkoutLoadMetricsUseCase = mockk<ComputeWorkoutLoadMetricsUseCase>()

    private val useCase =
        GetWorkoutDisplayMetricsUseCase(
            dailySummaryRepository = dailySummaryRepository,
            heartRateRepository = heartRateRepository,
            settingsRepo = settingsRepo,
            computeWorkoutLoadMetricsUseCase = computeWorkoutLoadMetricsUseCase,
        )

    @Test
    fun `executes calculations and fetches history in the scoring zone`() =
        runTest {
            val zoneId = ZoneId.of("Pacific/Honolulu")
            val workoutDate = LocalDate.of(2026, 6, 9)
            val startMs = Instant.parse("2026-06-10T05:00:00Z").toEpochMilli()

            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "RUNNING",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 130f,
                )

            val prefs = UserPreferences(scoringZoneId = "Pacific/Honolulu")
            val midnight = workoutDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val summary = mockk<DailySummary>()
            every { summary.rhrBpm } returns 55f
            coEvery { dailySummaryRepository.getByDate(midnight) } returns summary

            val fortyTwoDaysAgo =
                workoutDate
                    .minusDays(ScoringConstants.CHRONIC_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            coEvery { dailySummaryRepository.getSince(fortyTwoDaysAgo) } returns emptyList()

            val dbSamples =
                listOf(
                    HeartRateRecordData(
                        id = "hr-1",
                        timestampMs = startMs + 1000L,
                        beatsPerMinute = 130,
                        recordType = "EXERCISE",
                    ),
                )
            coEvery { heartRateRepository.getByTimeRange(workout.startTime, workout.endTime) } returns dbSamples

            val loadMetrics =
                ComputeWorkoutLoadMetricsUseCase.WorkoutLoadMetrics(
                    preciseTrimp = 50f,
                    roundedTrimp = 50,
                    preciseGainedStrain = 0.36f,
                    roundedGainedStrain = 0.36f,
                    gainedStrainDisplay = "0.36",
                    classification =
                        WorkoutLoadClassification(
                            totalTrimp = 50.0,
                            trimpPerMinute = 1.2,
                            baseLoad = WorkoutLoadLevel.LIGHT,
                            intensity = WorkoutIntensityLevel.LIGHT,
                            finalLoad = WorkoutLoadLevel.LIGHT,
                            wasPromoted = false,
                        ),
                )
            every {
                computeWorkoutLoadMetricsUseCase.execute(
                    workout = workout,
                    workoutDate = workoutDate,
                    samples = any(),
                    prefs = prefs,
                    restingHrBaseline = 55f,
                    trimpByDate = any(),
                )
            } returns loadMetrics

            val result =
                useCase.execute(
                    workout = workout,
                    preferences = prefs,
                )

            assertEquals(50f, result.preciseTrimp)
            assertEquals(50, result.computedTrimp)
            assertEquals("50", result.trimpDisplay)
            assertEquals(0.36f, result.gainedStrain)
            assertEquals("0.36", result.gainedStrainDisplay)
            assertEquals(WorkoutLoadLevel.LIGHT, result.classification?.finalLoad)

            coVerify {
                dailySummaryRepository.getByDate(midnight)
                dailySummaryRepository.getSince(fortyTwoDaysAgo)
                heartRateRepository.getByTimeRange(workout.startTime, workout.endTime)
            }
        }

    @Test
    fun `uses pre-fetched historicalSummaries instead of querying the repository`() =
        runTest {
            val zoneId = ZoneId.of("Pacific/Honolulu")
            val workoutDate = LocalDate.of(2026, 6, 9)
            val startMs = Instant.parse("2026-06-10T05:00:00Z").toEpochMilli()

            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "RUNNING",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 130f,
                )

            val prefs = UserPreferences(scoringZoneId = "Pacific/Honolulu")
            val midnight = workoutDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val summary = mockk<DailySummary>()
            every { summary.rhrBpm } returns 55f
            coEvery { dailySummaryRepository.getByDate(midnight) } returns summary

            val dbSamples =
                listOf(
                    HeartRateRecordData(
                        id = "hr-1",
                        timestampMs = startMs + 1000L,
                        beatsPerMinute = 130,
                        recordType = "EXERCISE",
                    ),
                )
            coEvery { heartRateRepository.getByTimeRange(workout.startTime, workout.endTime) } returns dbSamples

            val loadMetrics =
                ComputeWorkoutLoadMetricsUseCase.WorkoutLoadMetrics(
                    preciseTrimp = 50f,
                    roundedTrimp = 50,
                    preciseGainedStrain = 0.36f,
                    roundedGainedStrain = 0.36f,
                    gainedStrainDisplay = "0.36",
                    classification = null,
                )
            every {
                computeWorkoutLoadMetricsUseCase.execute(
                    workout = workout,
                    workoutDate = workoutDate,
                    samples = any(),
                    prefs = prefs,
                    restingHrBaseline = 55f,
                    // Pin the actual map content: `any()` here would silently accept a wrong or
                    // unclamped history window.
                    trimpByDate =
                        match {
                            it == mapOf(workoutDate to 10f, workoutDate.minusDays(3) to 4f)
                        },
                )
            } returns loadMetrics

            val preFetched =
                listOf(
                    DailySummary(date = workoutDate, trimpWorkoutOnly = 10f),
                    DailySummary(date = workoutDate.minusDays(3), trimpWorkoutOnly = 4f),
                )

            val result =
                useCase.execute(
                    workout = workout,
                    preferences = prefs,
                    historicalSummaries = preFetched,
                )

            assertEquals(50f, result.preciseTrimp)
            coVerify(exactly = 0) { dailySummaryRepository.getSince(any()) }
        }

    @Test
    fun `clamps pre-fetched historicalSummaries to the same 42-day window as the self-fetch path`() =
        runTest {
            val zoneId = ZoneId.of("Pacific/Honolulu")
            val workoutDate = LocalDate.of(2026, 6, 9)
            val startMs = Instant.parse("2026-06-10T05:00:00Z").toEpochMilli()

            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "RUNNING",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 130f,
                )

            val prefs = UserPreferences(scoringZoneId = "Pacific/Honolulu")
            val midnight = workoutDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val summary = mockk<DailySummary>()
            every { summary.rhrBpm } returns 55f
            coEvery { dailySummaryRepository.getByDate(midnight) } returns summary
            coEvery { heartRateRepository.getByTimeRange(any(), any()) } returns emptyList()

            val loadMetrics =
                ComputeWorkoutLoadMetricsUseCase.WorkoutLoadMetrics(
                    preciseTrimp = 50f,
                    roundedTrimp = 50,
                    preciseGainedStrain = 0.36f,
                    roundedGainedStrain = 0.36f,
                    gainedStrainDisplay = "0.36",
                    classification = null,
                )

            val inWindow = workoutDate.minusDays(ScoringConstants.CHRONIC_DAYS)
            val outOfWindow = workoutDate.minusDays(ScoringConstants.CHRONIC_DAYS + 1)

            every {
                computeWorkoutLoadMetricsUseCase.execute(
                    workout = workout,
                    workoutDate = workoutDate,
                    samples = any(),
                    prefs = prefs,
                    restingHrBaseline = 55f,
                    trimpByDate =
                        match {
                            it.size == 1 && it.containsKey(inWindow) && !it.containsKey(outOfWindow)
                        },
                )
            } returns loadMetrics

            val result =
                useCase.execute(
                    workout = workout,
                    preferences = prefs,
                    historicalSummaries =
                        listOf(
                            DailySummary(date = outOfWindow, trimpWorkoutOnly = 99f),
                            DailySummary(date = inWindow, trimpWorkoutOnly = 7f),
                        ),
                )

            assertEquals(50f, result.preciseTrimp)
        }

    @Test
    fun `gainedStrain is identical whether history is self-fetched or passed in pre-fetched`() =
        runTest {
            // C1 regression guard: the same workout must show the same gained strain on the
            // Dashboard / History list (which pass a wider pre-fetched window) as on Workout
            // Detail (which lets the use case fetch its own 42-day window).
            val zoneId = ZoneId.of("Pacific/Honolulu")
            val workoutDate = LocalDate.of(2026, 6, 9)
            val startMs = Instant.parse("2026-06-10T05:00:00Z").toEpochMilli()

            val workout =
                WorkoutData(
                    id = "run-1",
                    startTime = startMs,
                    endTime = startMs + 30 * 60 * 1000L,
                    exerciseType = "RUNNING",
                    durationMinutes = 30,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 50f,
                    avgHr = 130f,
                )

            val prefs = UserPreferences(scoringZoneId = "Pacific/Honolulu")
            val midnight = workoutDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val summary = mockk<DailySummary>()
            every { summary.rhrBpm } returns 55f
            coEvery { dailySummaryRepository.getByDate(midnight) } returns summary
            coEvery { heartRateRepository.getByTimeRange(any(), any()) } returns emptyList()

            // The DB-backed 42-day window the self-fetch path would return.
            val dbWindow =
                (0L..ScoringConstants.CHRONIC_DAYS).map { daysAgo ->
                    DailySummary(
                        date = workoutDate.minusDays(daysAgo),
                        trimpWorkoutOnly = 10f + daysAgo,
                    )
                }
            // A caller's wider window: the same rows plus older history the caller needed for
            // its own chart range but this workout must not see.
            val callerWindow =
                dbWindow +
                    (1L..6L).map { extra ->
                        DailySummary(
                            date = workoutDate.minusDays(ScoringConstants.CHRONIC_DAYS + extra),
                            trimpWorkoutOnly = 500f,
                        )
                    }

            val fortyTwoDaysAgo =
                workoutDate
                    .minusDays(ScoringConstants.CHRONIC_DAYS)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            coEvery { dailySummaryRepository.getSince(fortyTwoDaysAgo) } returns dbWindow

            // Real (non-mocked) EMA math: the gained strain must actually be a function of the
            // trimpByDate map it receives, otherwise this test proves nothing.
            val ras = RasScoringStrategy()
            val realScoringCalculator =
                mockk<ScoringCalculator>(relaxed = true).also {
                    every { it.computeAtlEmaWithDecay(any(), any(), any()) } answers {
                        ras.computeAtlEmaWithDecay(firstArg(), secondArg(), thirdArg())
                    }
                    every { it.computeCtlEmaWithDecay(any(), any(), any()) } answers {
                        ras.computeCtlEmaWithDecay(firstArg(), secondArg(), thirdArg())
                    }
                    every { it.computeStrainRatio(any(), any()) } answers {
                        ras.computeStrainRatio(firstArg(), secondArg())
                    }
                }
            val realComputeUseCase =
                ComputeWorkoutLoadMetricsUseCase(
                    computeWorkoutTrimpUseCase = ComputeWorkoutTrimpUseCase(),
                    scoringCalculator = realScoringCalculator,
                    workoutLoadClassifier = WorkoutLoadClassifier(),
                )
            val realUseCase =
                GetWorkoutDisplayMetricsUseCase(
                    dailySummaryRepository = dailySummaryRepository,
                    heartRateRepository = heartRateRepository,
                    settingsRepo = settingsRepo,
                    computeWorkoutLoadMetricsUseCase = realComputeUseCase,
                )

            val selfFetched =
                realUseCase.execute(
                    workout = workout,
                    preferences = prefs,
                    historicalSummaries = null,
                )
            val preFetched =
                realUseCase.execute(
                    workout = workout,
                    preferences = prefs,
                    historicalSummaries = callerWindow,
                )

            assertEquals(selfFetched.gainedStrain, preFetched.gainedStrain)
            assertEquals(selfFetched.gainedStrainDisplay, preFetched.gainedStrainDisplay)
        }
}
