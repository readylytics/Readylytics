package app.readylytics.health.domain.airecommendation

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.model.domain.scoring.LoadCoverageConfidence
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.model.PermittedRecommendation
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.core.model.domain.scoring.WorkoutIntensityLevel
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassifier
import app.readylytics.health.core.model.domain.scoring.WorkoutLoadLevel
import app.readylytics.health.core.scoring.domain.scoring.components.Phase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class GetDailyPromptDataUseCaseTest {
    private val dailySummaryRepository = mockk<DailySummaryRepository>()
    private val workoutRepository = mockk<WorkoutRepository>()
    private val preferencesReader = mockk<UserPreferencesReader>()
    private val getWorkoutDisplayMetricsUseCase = mockk<GetWorkoutDisplayMetricsUseCase>()
    private val patternSummaryUseCase = ComputeWorkoutPatternSummaryUseCase()
    private val recommendedLoadCalculator = RecommendedLoadCalculator(WorkoutLoadClassifier())
    private val useCase =
        GetDailyPromptDataUseCase(
            dailySummaryRepository = dailySummaryRepository,
            workoutRepository = workoutRepository,
            preferencesReader = preferencesReader,
            getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
            patternSummaryUseCase = patternSummaryUseCase,
            recommendedLoadCalculator = recommendedLoadCalculator,
        )

    private val today = LocalDate.of(2026, 8, 9)
    private val todayMidnight = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val yesterday = today.minusDays(1)
    private val yesterdayMidnight = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val tomorrowMidnight = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val lookbackStartMidnight =
        today.minusMonths(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        every { preferencesReader.userPreferences } returns
            flowOf(UserPreferences(scoringZoneId = "UTC", strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY))
    }

    @Test
    fun `execute reads today and yesterday persisted summaries`() = runTest {
        val todaySummary = summary(today)
        val yesterdaySummary = summary(yesterday)
        coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns todaySummary
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns yesterdaySummary
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        useCase.execute(today)

        coVerify(exactly = 1) { dailySummaryRepository.getByDate(todayMidnight) }
        coVerify(exactly = 1) { dailySummaryRepository.getByDate(yesterdayMidnight) }
    }

    @Test
    fun `execute queries the pattern workout window once and derives day subsets in memory`() = runTest {
        coEvery { dailySummaryRepository.getByDate(any()) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        useCase.execute(today)

        coVerify(exactly = 1) { workoutRepository.getInRange(lookbackStartMidnight, tomorrowMidnight) }
        coVerify(exactly = 0) { workoutRepository.getInRange(yesterdayMidnight, todayMidnight) }
        coVerify(exactly = 0) { workoutRepository.getInRange(todayMidnight, tomorrowMidnight) }
    }

    @Test
    fun `execute selects configured training-load source and maps recovery flags`() = runTest {
        every { preferencesReader.userPreferences } returns
            flowOf(UserPreferences(scoringZoneId = "UTC", strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE))
        val todaySummary =
            summary(today, readinessEverydayHr = 80f, flags = setOf(RecoveryFlag.ILLNESS_ONSET))
        coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns todaySummary
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        val result = useCase.execute(today)

        assertEquals("Everyday heart-rate load", result.activeTrainingLoadSource)
        assertEquals(80f, result.today.readinessScore)
        assertEquals(RecoveryFlag.ILLNESS_ONSET, result.activeRecoveryFlags.single().flagName)
        assertEquals(Phase.MATURE.displayName, result.calibrationPhase)
        assertEquals("HIGH", result.advisorDataConfidence)
        assertEquals("SWEET_SPOT", result.loadState.loadContext)
        assertEquals("NEUTRAL", result.today.readinessBand)
        assertEquals(PermittedRecommendation.REST, result.today.permittedRecommendation)
        assertEquals(PermittedRecommendation.REST, result.today.recommendedAction)
        assertEquals(RecommendedLoadPromptData(qualitative = "NORMAL"), result.loadState.recommendedLoad)
    }

    @Test
    fun `execute applies every everyday coverage tier to advisor confidence`() = runTest {
        every { preferencesReader.userPreferences } returns
            flowOf(UserPreferences(scoringZoneId = "UTC", strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE))
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        listOf(
            LoadCoverageConfidence.NONE to "MEDIUM",
            LoadCoverageConfidence.LOW to "MEDIUM",
            LoadCoverageConfidence.MEDIUM to "HIGH",
            LoadCoverageConfidence.HIGH to "HIGH",
        ).forEach { (coverage, expectedConfidence) ->
            coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns
                summary(today, everydayLoadConfidence = coverage.name)

            assertEquals(expectedConfidence, useCase.execute(today).advisorDataConfidence)
        }
    }

    @Test
    fun `execute does not apply everyday coverage when workout-only source is selected`() = runTest {
        coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns
            summary(today, everydayLoadConfidence = LoadCoverageConfidence.NONE.name)
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        val result = useCase.execute(today)

        assertEquals("HIGH", result.advisorDataConfidence)
    }

    @Test
    fun `execute combines everyday none coverage with missing recovery signals`() = runTest {
        every { preferencesReader.userPreferences } returns
            flowOf(UserPreferences(scoringZoneId = "UTC", strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE))
        coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns
            summary(
                today,
                everydayLoadConfidence = LoadCoverageConfidence.NONE.name,
                flags = setOf(RecoveryFlag.HRV_MISSING),
            )
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        val result = useCase.execute(today)

        assertEquals("LOW", result.advisorDataConfidence)
    }

    @Test
    fun `execute reuses display metrics for every yesterday workout`() = runTest {
        val todaySummary = summary(today)
        coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns todaySummary
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        val workouts = listOf(workoutData("w1"), workoutData("w2"))
        coEvery { workoutRepository.getInRange(lookbackStartMidnight, tomorrowMidnight) } returns workouts
        coEvery { getWorkoutDisplayMetricsUseCase.execute(any(), any(), any(), any()) } returns
            displayMetrics()

        val result = useCase.execute(today)

        assertEquals(2, result.yesterdayWorkouts.size)
        coVerify(exactly = 2) {
            getWorkoutDisplayMetricsUseCase.execute(any(), any(), any(), any())
        }
    }

    @Test
    fun `execute maps workout display metrics into the prompt block`() = runTest {
        coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns summary(today)
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(lookbackStartMidnight, tomorrowMidnight) } returns
            listOf(workoutData("w1"))
        coEvery { getWorkoutDisplayMetricsUseCase.execute(any(), any(), any(), any()) } returns
            displayMetrics()

        val result = useCase.execute(today)

        val block = result.yesterdayWorkouts.single()
        assertEquals(130f, block.modelTrimp)
        assertEquals("0.4", block.roundedGainedStrain)
        assertEquals("MODERATE", block.loadClassification)
        assertEquals("MODERATE", block.intensity)
        assertEquals("Run", block.workout.exerciseType)
    }

    @Test
    fun `execute preserves null summaries and empty workout lists`() = runTest {
        coEvery { dailySummaryRepository.getByDate(any()) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        val result = useCase.execute(today)

        assertTrue("yesterdayWorkouts not empty", result.yesterdayWorkouts.isEmpty())
        assertNull("yesterdaySleep not null", result.yesterdaySleep)
        assertNull("readinessScore not null", result.today.readinessScore)
        assertTrue("activeRecoveryFlags not empty", result.activeRecoveryFlags.isEmpty())
        assertEquals(0, result.workoutPattern.totalWorkoutsInWindow)
        assertEquals("LOW", result.advisorDataConfidence)
        assertEquals(0, result.today.todayCompletedWorkouts)
        assertEquals(0f, result.today.todayTrimp)
        assertNull("recommendedAction not null", result.today.recommendedAction)
        assertNull("recommendedLoad not null", result.loadState.recommendedLoad)
        assertNull("todayTrainingMinutes not null", result.today.todayTrainingMinutes)
        assertNull("dataCurrentUntil not null", result.today.dataCurrentUntil)
        assertNull("loadContext not null", result.loadState.loadContext)
    }

    @Test
    fun `execute aggregates todays workouts into today block`() = runTest {
        coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns summary(today)
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        val w1 =
            workoutData("w1").copy(
                startTime = todayMidnight + 1000L,
                endTime = todayMidnight + 1000L,
                trimp = 50f,
                durationMinutes = 30,
            )
        val w2 =
            workoutData("w2").copy(
                startTime = todayMidnight + 2000L,
                endTime = todayMidnight + 2000L,
                trimp = 70f,
                durationMinutes = 40,
            )
        coEvery { workoutRepository.getInRange(lookbackStartMidnight, tomorrowMidnight) } returns
            listOf(w1, w2)

        val result = useCase.execute(today)

        assertEquals(2, result.today.todayCompletedWorkouts)
        assertEquals(120f, result.today.todayTrimp)
        assertEquals(70, result.today.todayTrainingMinutes)
        assertEquals(java.time.Instant.ofEpochMilli(todayMidnight + 2000L).toString(), result.today.dataCurrentUntil)
        assertEquals(RecommendedLoadPromptData(qualitative = "LIGHT"), result.loadState.recommendedLoad)
    }

    @Test
    fun `execute treats no today workouts as known zero load`() = runTest {
        coEvery { dailySummaryRepository.getByDate(todayMidnight) } returns summary(today)
        coEvery { dailySummaryRepository.getByDate(yesterdayMidnight) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        val result = useCase.execute(today)

        assertEquals(0, result.today.todayCompletedWorkouts)
        assertEquals(0f, result.today.todayTrimp)
        assertEquals(RecommendedLoadPromptData(qualitative = "NORMAL"), result.loadState.recommendedLoad)
    }

    @Test
    fun `execute uses the persisted sync watermark when no workout has completed today`() = runTest {
        val syncWatermark = java.time.Instant.parse("2026-08-09T14:25:00Z")
        every { preferencesReader.userPreferences } returns
            flowOf(
                UserPreferences(
                    scoringZoneId = "UTC",
                    strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY,
                    lastSyncTimestamp = syncWatermark.toEpochMilli(),
                ),
            )
        coEvery { dailySummaryRepository.getByDate(any()) } returns null
        coEvery { dailySummaryRepository.getSince(any()) } returns emptyList()
        coEvery { workoutRepository.getInRange(any(), any()) } returns emptyList()

        val result = useCase.execute(today)

        assertEquals(syncWatermark.toString(), result.today.dataCurrentUntil)
    }

    private fun summary(
        date: LocalDate,
        readinessEverydayHr: Float? = null,
        flags: Set<RecoveryFlag> = emptySet(),
        everydayLoadConfidence: String = "High",
    ): DailySummary =
        DailySummary(
            date = date,
            sleepScore = 80f,
            nocturnalHrv = 45,
            sleepDurationMinutes = 420,
            deepSleepPercent = 18f,
            remSleepPercent = 20f,
            hrvBaseline = 44,
            restingHeartRate = 52,
            restingHrRatio = 1.02f,
            recoveryFlags = flags,
            sRest = 0.8f,
            isCalibrating = false,
            avgSleepingSpo2 = 96f,
            hrvMuMssd = 44.2f,
            hrvSigmaMssd = 0.3f,
            rhrSigma = 0.9f,
            baselineCalculatedAtDate = LocalDate.of(2026, 7, 15),
            snapshotProfile = "Active",
            snapshotCalibrationPhase = Phase.MATURE.name,
            baselineObservationCount = 60,
            trimpWorkoutOnly = 100f,
            trimpEverydayHr = 110f,
            totalRasWorkoutOnly = 350f,
            totalRasEverydayHr = 410f,
            atlWorkoutOnly = 85f,
            atlEverydayHr = 90f,
            ctlWorkoutOnly = 80f,
            ctlEverydayHr = 82f,
            strainRatioWorkoutOnly = 1.06f,
            strainRatioEverydayHr = 1.1f,
            loadScoreWorkoutOnly = 88f,
            loadScoreEverydayHr = 85f,
            readinessWorkoutOnly = 78f,
            readinessEverydayHr = readinessEverydayHr,
            everydayCoverageMinutes = 120,
            everydayLoadConfidence = everydayLoadConfidence,
        )

    private fun workoutData(id: String): WorkoutData =
        WorkoutData(
            id = id,
            startTime = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 60_000L,
            endTime = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000L,
            exerciseType = "Run",
            durationMinutes = 45,
            zone1Minutes = 10f,
            zone2Minutes = 15f,
            zone3Minutes = 10f,
            zone4Minutes = 5f,
            zone5Minutes = 0f,
            trimp = 120f,
            avgHr = 150f,
        )

    private fun displayMetrics(): WorkoutDisplayMetrics =
        WorkoutDisplayMetrics(
            preciseTrimp = 130f,
            computedTrimp = 130,
            trimpDisplay = "130",
            gainedStrain = 0.38f,
            gainedStrainDisplay = "0.4",
            classification =
                WorkoutLoadClassification(
                    totalTrimp = 130.0,
                    trimpPerMinute = 2.0,
                    baseLoad = WorkoutLoadLevel.MODERATE,
                    intensity = WorkoutIntensityLevel.MODERATE,
                    finalLoad = WorkoutLoadLevel.MODERATE,
                    wasPromoted = false,
                ),
        )
}
