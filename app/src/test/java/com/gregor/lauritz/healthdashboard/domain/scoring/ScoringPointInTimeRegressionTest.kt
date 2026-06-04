package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.*
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity
import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.data.repository.ScoringRepositoryImpl
import com.gregor.lauritz.healthdashboard.domain.scoring.sleep.SleepPercentileRhrCalculator
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class ScoringPointInTimeRegressionTest {
    private val workoutDao = mockk<WorkoutDao>(relaxed = true)
    private val sleepSessionDao = mockk<SleepSessionDao>(relaxed = true)
    private val dailySummaryDao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val baselineComputer = mockk<BaselineComputer>(relaxed = true)
    private val computeSleepMetricsUseCase = mockk<ComputeSleepMetricsUseCase>(relaxed = true)
    private val scoringConfigFactory = mockk<ScoringConfigFactory>(relaxed = true)
    private val computeWorkoutTrimpUseCase = ComputeWorkoutTrimpUseCase()
    private val heartRateDao = mockk<HeartRateDao>(relaxed = true)
    private val hrvDao = mockk<HrvDao>(relaxed = true)
    private val weightRecordDao = mockk<WeightRecordDao>(relaxed = true)
    private val bodyFatRecordDao = mockk<BodyFatRecordDao>(relaxed = true)
    private val bloodPressureRecordDao = mockk<BloodPressureRecordDao>(relaxed = true)
    private val oxygenSaturationRecordDao = mockk<OxygenSaturationRecordDao>(relaxed = true)
    private val sleepPercentileRhrCalculator = mockk<SleepPercentileRhrCalculator>(relaxed = true)

    private lateinit var repo: ScoringRepositoryImpl

    @Before
    fun setup() {
        repo =
            ScoringRepositoryImpl(
                workoutDao,
                sleepSessionDao,
                dailySummaryDao,
                settingsRepo,
                scoringCalculator,
                baselineComputer,
                computeSleepMetricsUseCase,
                scoringConfigFactory,
                computeWorkoutTrimpUseCase,
                heartRateDao,
                hrvDao,
                weightRecordDao,
                bodyFatRecordDao,
                bloodPressureRecordDao,
                oxygenSaturationRecordDao,
                sleepPercentileRhrCalculator,
            )
    }

    @Test
    fun verifyHistoricalRecomputationIsStableAfterPreferencesChange() =
        runTest {
            val today = LocalDate.now()
            val zoneId = ZoneId.systemDefault()
            val dayMidnightMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

            // 1. Setup a frozen snapshot daily summary for today
            val frozenSnapshot =
                DailySummaryEntity(
                    dateMidnightMs = dayMidnightMs,
                    baselineCalculatedAtDate = today,
                    hrMax = 190f,
                    paiScalingFactor = 0.2f,
                    rhrBpm = 60f,
                    baselineObservationCount = 10,
                    baselineVersion = 2,
                )
            coEvery { dailySummaryDao.getByDate(dayMidnightMs) } returns frozenSnapshot

            // 2. Setup mock workouts/samples
            val workout =
                WorkoutRecordEntity(
                    id = "w1",
                    startTime = dayMidnightMs + 1000L,
                    endTime = dayMidnightMs + 3600000L,
                    exerciseType = "RUN",
                    durationMinutes = 60,
                    zone1Minutes = 0f,
                    zone2Minutes = 0f,
                    zone3Minutes = 0f,
                    zone4Minutes = 0f,
                    zone5Minutes = 0f,
                    trimp = 20f,
                    avgHr = 130f,
                )
            coEvery { workoutDao.getWorkoutsInRange(any(), any()) } returns listOf(workout)
            // triggers fallback using workout.trimp
            coEvery { heartRateDao.getByTimeRange(any(), any()) } returns emptyList()

            // 3. User Preferences setup: first run has ATHLETE profile
            val initialPrefs =
                UserPreferences(
                    physiologyProfile = PhysiologyProfile.ATHLETE,
                    maxHeartRate = 195,
                    paiScalingFactor = 0.25f,
                    rhrBaselineOverride = 55f,
                    gender = Gender.MALE,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(initialPrefs)
            val mockConfig = mockk<ScoringConfig>(relaxed = true)
            every { mockConfig.paiScalingFactor } returns 0.25f
            every { scoringConfigFactory.build(any(), any(), any(), any()) } returns mockConfig

            // Compute daily summary under initial preferences
            val result1 = repo.computeDailySummary(today)

            // 4. Mutate User Preferences: second run has SEDENTARY profile and different max HR
            val mutatedPrefs =
                UserPreferences(
                    physiologyProfile = PhysiologyProfile.SEDENTARY,
                    maxHeartRate = 170,
                    paiScalingFactor = 0.15f,
                    rhrBaselineOverride = 72f,
                    gender = Gender.FEMALE,
                )
            coEvery { settingsRepo.userPreferences } returns flowOf(mutatedPrefs)

            // Compute daily summary under mutated preferences
            val result2 = repo.computeDailySummary(today)

            // Assert that the computed metrics on this historical day are unchanged
            assertEquals(result1.paiScore, result2.paiScore, "PAI Score must remain unchanged")
            assertEquals(result1.totalPai, result2.totalPai, "Total PAI must remain unchanged")
        }
}
