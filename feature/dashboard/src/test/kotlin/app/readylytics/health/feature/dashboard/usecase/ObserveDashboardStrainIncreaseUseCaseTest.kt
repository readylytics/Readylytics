package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveDashboardStrainIncreaseUseCaseTest {
    private val testDispatcher = StandardTestDispatcher()
    private val expectedHistoryStartMs =
        LocalDate
            .of(2026, 6, 12)
            .atStartOfDay(ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()
    private val workoutRepository = mockk<WorkoutRepository>()
    private val dailySummaryRepository = mockk<DailySummaryRepository>()
    private val getWorkoutDisplayMetricsUseCase = mockk<GetWorkoutDisplayMetricsUseCase>()
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)

    @Test
    fun `returns null before seven days of workout tenure`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 7, 30)
            val zoneId = ZoneId.of("UTC")
            val recentWorkout =
                workout(
                    "recent",
                    selectedDate
                        .minusDays(5)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                )
            coEvery { workoutRepository.getEarliestWorkoutTimestamp() } returns recentWorkout.startTime
            every {
                workoutRepository.observeSince(expectedHistoryStartMs)
            } returns flowOf(listOf(recentWorkout))
            every {
                dailySummaryRepository.observeSince(expectedHistoryStartMs)
            } returns flowOf(emptyList())

            val result =
                createUseCase()
                    .invoke(
                        selectedDate = flowOf(selectedDate),
                        preferences = flowOf(UserPreferences(scoringZoneId = "UTC")),
                    ).first()

            assertNull(result)
        }

    @Test
    fun `new workout history can cross the seven-day tenure guard without resubscribing`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 7, 30)
            val zoneId = ZoneId.of("UTC")
            val workouts = MutableStateFlow(emptyList<WorkoutData>())
            coEvery { workoutRepository.getEarliestWorkoutTimestamp() } returnsMany
                listOf(
                    selectedDate
                        .minusDays(5)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                    selectedDate
                        .minusDays(6)
                        .atStartOfDay(zoneId)
                        .toInstant()
                        .toEpochMilli(),
                )
            every { workoutRepository.observeSince(expectedHistoryStartMs) } returns workouts
            every {
                dailySummaryRepository.observeSince(expectedHistoryStartMs)
            } returns flowOf(emptyList())
            val observed =
                createUseCase().invoke(
                    selectedDate = flowOf(selectedDate),
                    preferences = flowOf(UserPreferences(scoringZoneId = "UTC")),
                )

            val values =
                async {
                    withTimeout(1_000) {
                        observed.take(2).toList()
                    }
                }
            runCurrent()
            workouts.value =
                listOf(
                    workout(
                        id = "seven-days-back",
                        startTime =
                            selectedDate
                                .minusDays(6)
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli(),
                    ),
                )

            assertEquals(listOf(null, 0f), values.await())
            verify(exactly = 1) { workoutRepository.observeSince(expectedHistoryStartMs) }
        }

    @Test
    fun `workout-only mode sums rounded display gains from selected-day workouts`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 7, 30)
            val zoneId = ZoneId.of("UTC")
            val dayStart = selectedDate.atStartOfDay(zoneId)
            val firstWorkout = workout("first", dayStart.plusHours(7).toInstant().toEpochMilli())
            val secondWorkout = workout("second", dayStart.plusHours(18).toInstant().toEpochMilli())
            val previousDayWorkout =
                workout(
                    "previous",
                    dayStart
                        .minusDays(1)
                        .plusHours(18)
                        .toInstant()
                        .toEpochMilli(),
                )
            val oldWorkout =
                workout(
                    "old",
                    dayStart
                        .minusDays(10)
                        .toInstant()
                        .toEpochMilli(),
                )
            coEvery { workoutRepository.getEarliestWorkoutTimestamp() } returns oldWorkout.startTime
            every { workoutRepository.observeSince(expectedHistoryStartMs) } returns
                flowOf(listOf(oldWorkout, previousDayWorkout, firstWorkout, secondWorkout))
            every {
                dailySummaryRepository.observeSince(expectedHistoryStartMs)
            } returns flowOf(emptyList())
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = firstWorkout,
                    preferences = any(),
                    historicalSummaries = any(),
                )
            } returns displayMetrics(0.09f)
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = secondWorkout,
                    preferences = any(),
                    historicalSummaries = any(),
                )
            } returns displayMetrics(0.14f)

            val result =
                createUseCase()
                    .invoke(
                        selectedDate = flowOf(selectedDate),
                        preferences =
                            flowOf(
                                UserPreferences(
                                    scoringZoneId = "UTC",
                                    strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY,
                                ),
                            ),
                    ).first()

            assertEquals(0.23f, result!!, 0.001f)
        }

    @Test
    fun `workout-only tenure survives a long gap with no workout inside the fetch window`() =
        runTest(testDispatcher) {
            // A returning user: two years of history, then a break longer than the 48-day fetch
            // window, then a workout today. Tenure must come from the unbounded DB query, not
            // from the fetch-window-bounded workouts list (which would understate it).
            val selectedDate = LocalDate.of(2026, 7, 30)
            val zoneId = ZoneId.of("UTC")
            val todaysWorkout =
                workout(
                    "resumed",
                    selectedDate
                        .atStartOfDay(zoneId)
                        .plusHours(7)
                        .toInstant()
                        .toEpochMilli(),
                )
            coEvery { workoutRepository.getEarliestWorkoutTimestamp() } returns
                selectedDate
                    .minusYears(2)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
            every { workoutRepository.observeSince(expectedHistoryStartMs) } returns
                flowOf(listOf(todaysWorkout))
            every {
                dailySummaryRepository.observeSince(expectedHistoryStartMs)
            } returns flowOf(emptyList())
            coEvery {
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = todaysWorkout,
                    preferences = any(),
                    historicalSummaries = any(),
                )
            } returns displayMetrics(0.21f)

            val result =
                createUseCase()
                    .invoke(
                        selectedDate = flowOf(selectedDate),
                        preferences =
                            flowOf(
                                UserPreferences(
                                    scoringZoneId = "UTC",
                                    strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY,
                                ),
                            ),
                    ).first()

            assertEquals(0.21f, result!!, 0.001f)
        }

    @Test
    fun `everyday-heart-rate mode subtracts zero-day ratio from current-day ratio`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 7, 30)
            val zoneId = ZoneId.of("UTC")
            val summaries =
                listOf(
                    DailySummary(
                        date = selectedDate.minusDays(10),
                        trimpEverydayHr = 5f,
                    ),
                    DailySummary(
                        date = selectedDate.minusDays(1),
                        trimpEverydayHr = 20f,
                    ),
                    DailySummary(
                        date = selectedDate,
                        trimpEverydayHr = 45f,
                    ),
                )
            every {
                workoutRepository.observeSince(expectedHistoryStartMs)
            } returns flowOf(emptyList())
            every { dailySummaryRepository.observeSince(expectedHistoryStartMs) } returns
                flowOf(summaries)
            every {
                scoringCalculator.computeCtlEmaSeries(any(), selectedDate.minusDays(6), selectedDate)
            } returns mapOf(selectedDate to 10f)
            every {
                scoringCalculator.computeAtlEmaSeries(any(), selectedDate.minusDays(6), selectedDate)
            } returns mapOf(selectedDate to 15f)
            every {
                scoringCalculator.computeCtlEmaWithDecay(match { it[selectedDate] == 0f }, selectedDate)
            } returns 10f
            every {
                scoringCalculator.computeAtlEmaWithDecay(match { it[selectedDate] == 0f }, selectedDate)
            } returns 12f
            every { scoringCalculator.computeStrainRatio(15f, 10f) } returns 1.5f
            every { scoringCalculator.computeStrainRatio(12f, 10f) } returns 1.2f

            val result =
                createUseCase()
                    .invoke(
                        selectedDate = flowOf(selectedDate),
                        preferences =
                            flowOf(
                                UserPreferences(
                                    scoringZoneId = "UTC",
                                    strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
                                ),
                            ),
                    ).first()

            assertEquals(0.3f, result!!, 0.001f)
        }

    @Test
    fun `everyday-heart-rate mode reports a delta with thirty days of summaries and zero workouts`() =
        runTest(testDispatcher) {
            val selectedDate = LocalDate.of(2026, 7, 30)
            val zoneId = ZoneId.of("UTC")
            val summaries =
                (0..29).map { daysAgo ->
                    DailySummary(
                        date = selectedDate.minusDays(daysAgo.toLong()),
                        trimpEverydayHr = 20f,
                    )
                }
            every {
                workoutRepository.observeSince(expectedHistoryStartMs)
            } returns flowOf(emptyList())
            every { dailySummaryRepository.observeSince(expectedHistoryStartMs) } returns
                flowOf(summaries)
            every {
                scoringCalculator.computeCtlEmaSeries(any(), selectedDate.minusDays(6), selectedDate)
            } returns mapOf(selectedDate to 10f)
            every {
                scoringCalculator.computeAtlEmaSeries(any(), selectedDate.minusDays(6), selectedDate)
            } returns mapOf(selectedDate to 15f)
            every {
                scoringCalculator.computeCtlEmaWithDecay(match { it[selectedDate] == 0f }, selectedDate)
            } returns 10f
            every {
                scoringCalculator.computeAtlEmaWithDecay(match { it[selectedDate] == 0f }, selectedDate)
            } returns 12f
            every { scoringCalculator.computeStrainRatio(15f, 10f) } returns 1.5f
            every { scoringCalculator.computeStrainRatio(12f, 10f) } returns 1.2f

            val result =
                createUseCase()
                    .invoke(
                        selectedDate = flowOf(selectedDate),
                        preferences =
                            flowOf(
                                UserPreferences(
                                    scoringZoneId = "UTC",
                                    strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
                                ),
                            ),
                    ).first()

            assertEquals(0.3f, result!!, 0.001f)
        }

    private fun createUseCase() =
        ObserveDashboardStrainIncreaseUseCase(
            workoutRepository = workoutRepository,
            dailySummaryRepository = dailySummaryRepository,
            getWorkoutDisplayMetricsUseCase = getWorkoutDisplayMetricsUseCase,
            scoringCalculator = scoringCalculator,
            defaultDispatcher = testDispatcher,
        )

    private fun workout(
        id: String,
        startTime: Long,
    ) = WorkoutData(
        id = id,
        startTime = startTime,
        endTime = startTime + 30 * 60_000L,
        exerciseType = "running",
        durationMinutes = 30,
        zone1Minutes = 0f,
        zone2Minutes = 0f,
        zone3Minutes = 0f,
        zone4Minutes = 0f,
        zone5Minutes = 0f,
        trimp = 0f,
        avgHr = 0f,
    )

    private fun displayMetrics(gainedStrain: Float) =
        WorkoutDisplayMetrics(
            preciseTrimp = 0f,
            computedTrimp = 0,
            trimpDisplay = "0",
            gainedStrain = gainedStrain,
            gainedStrainDisplay = gainedStrain.toString(),
            classification = null,
        )
}
