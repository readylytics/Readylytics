package app.readylytics.health.feature.workouts

import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.ScoringCalculator
import app.readylytics.health.domain.scoring.ScoringConstants
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WorkoutsStateFactoryTest {
    private val scoringCalculator = mockk<ScoringCalculator>(relaxed = true)
    private val zoneId = ZoneId.of("UTC")
    private val selectedDate = LocalDate.of(2026, 6, 10)

    @Before
    fun setUp() {
        every { scoringCalculator.computeCtlEmaSeries(any(), any(), any()) } returns emptyMap()
        every { scoringCalculator.computeAtlEmaSeries(any(), any(), any()) } returns emptyMap()
    }

    @Test
    fun `buildWorkoutsState pads daily trimp and daily strain ratio to 7 days for SEVEN_DAYS range`() {
        val result =
            buildWorkoutsState(
                WorkoutsStateInputs(
                    scoringCalculator = scoringCalculator,
                    latestSummary = null,
                    trimpSummaries = emptyList(),
                    rasSummaries = emptyList(),
                    prefs = UserPreferences(),
                    range = TimeRange.SEVEN_DAYS,
                    selectedDate = selectedDate,
                    zoneId = zoneId,
                    recentWorkouts = emptyList(),
                    currentPage = 1,
                    totalPages = 1,
                    earliestLocalDate = null,
                ),
            )

        assertEquals(7, result.dailyTrimp.size)
        assertEquals(7, result.dailyStrainRatio.size)
        assertEquals(selectedDate, result.selectedDate)
        assertEquals(TimeRange.SEVEN_DAYS, result.selectedRange)
    }

    @Test
    fun `buildWorkoutsState pads daily trimp and daily strain ratio to 30 days for THIRTY_DAYS range`() {
        val result =
            buildWorkoutsState(
                WorkoutsStateInputs(
                    scoringCalculator = scoringCalculator,
                    latestSummary = null,
                    trimpSummaries = emptyList(),
                    rasSummaries = emptyList(),
                    prefs = UserPreferences(),
                    range = TimeRange.THIRTY_DAYS,
                    selectedDate = selectedDate,
                    zoneId = zoneId,
                    recentWorkouts = emptyList(),
                    currentPage = 1,
                    totalPages = 1,
                    earliestLocalDate = null,
                ),
            )

        assertEquals(30, result.dailyTrimp.size)
        assertEquals(30, result.dailyStrainRatio.size)
    }

    @Test
    fun `buildWorkoutsState sets dailyStrainRatio to null when data tenure is less than 7 days`() {
        val summaries =
            listOf(
                DailySummary(date = selectedDate, trimpWorkoutOnly = 40f),
            )
        every { scoringCalculator.computeCtlEmaSeries(any(), any(), any()) } returns mapOf(selectedDate to 20f)
        every { scoringCalculator.computeAtlEmaSeries(any(), any(), any()) } returns mapOf(selectedDate to 30f)
        every { scoringCalculator.computeStrainRatio(30f, 20f) } returns 1.5f

        val result =
            buildWorkoutsState(
                WorkoutsStateInputs(
                    scoringCalculator = scoringCalculator,
                    latestSummary = null,
                    trimpSummaries = summaries,
                    rasSummaries = emptyList(),
                    prefs = UserPreferences(),
                    range = TimeRange.SEVEN_DAYS,
                    selectedDate = selectedDate,
                    zoneId = zoneId,
                    recentWorkouts = emptyList(),
                    currentPage = 1,
                    totalPages = 1,
                    earliestLocalDate = selectedDate.minusDays(5), // 6 days tenure < 7
                ),
            )

        val lastPoint = result.dailyStrainRatio.last()
        assertNull(lastPoint.value)
    }

    @Test
    fun `buildWorkoutsState computes dailyStrainRatio when data tenure is at least 7 days`() {
        val summaries =
            listOf(
                DailySummary(date = selectedDate, trimpWorkoutOnly = 40f),
            )
        every { scoringCalculator.computeCtlEmaSeries(any(), any(), any()) } returns mapOf(selectedDate to 20f)
        every { scoringCalculator.computeAtlEmaSeries(any(), any(), any()) } returns mapOf(selectedDate to 30f)
        every { scoringCalculator.computeStrainRatio(30f, 20f) } returns 1.5f

        val result =
            buildWorkoutsState(
                WorkoutsStateInputs(
                    scoringCalculator = scoringCalculator,
                    latestSummary = null,
                    trimpSummaries = summaries,
                    rasSummaries = emptyList(),
                    prefs = UserPreferences(),
                    range = TimeRange.SEVEN_DAYS,
                    selectedDate = selectedDate,
                    zoneId = zoneId,
                    recentWorkouts = emptyList(),
                    currentPage = 1,
                    totalPages = 1,
                    earliestLocalDate = selectedDate.minusDays(6), // 7 days tenure >= 7
                ),
            )

        val lastPoint = result.dailyStrainRatio.last()
        assertEquals(1.5f, lastPoint.value!!, 0.001f)
    }

    @Test
    fun `buildWorkoutsState maps latest summary and metrics with preferences`() {
        val latest =
            DailySummary(
                date = selectedDate,
                readinessWorkoutOnly = 80f,
                strainRatioWorkoutOnly = 1.2f,
                trimpWorkoutOnly = 45f,
            )

        val result =
            buildWorkoutsState(
                WorkoutsStateInputs(
                    scoringCalculator = scoringCalculator,
                    latestSummary = latest,
                    trimpSummaries = listOf(latest),
                    rasSummaries = emptyList(),
                    prefs = UserPreferences(),
                    range = TimeRange.SEVEN_DAYS,
                    selectedDate = selectedDate,
                    zoneId = zoneId,
                    recentWorkouts = emptyList(),
                    currentPage = 1,
                    totalPages = 1,
                    earliestLocalDate = selectedDate.minusDays(10),
                ),
            )

        assertEquals(latest, result.latestSummary)
        assertNotNull(result.latestMetrics)
        assertEquals(80, result.latestMetrics?.readinessRounded)
    }

    @Test
    fun `buildWorkoutsState extracts yesterday metrics from rasSummaries`() {
        val yesterday = selectedDate.minusDays(1)
        val yesterdaySummary =
            DailySummary(
                date = yesterday,
                readinessWorkoutOnly = 75f,
                strainRatioWorkoutOnly = 0.85f,
            )

        val result =
            buildWorkoutsState(
                WorkoutsStateInputs(
                    scoringCalculator = scoringCalculator,
                    latestSummary = null,
                    trimpSummaries = emptyList(),
                    rasSummaries = listOf(yesterdaySummary),
                    prefs = UserPreferences(),
                    range = TimeRange.SEVEN_DAYS,
                    selectedDate = selectedDate,
                    zoneId = zoneId,
                    recentWorkouts = emptyList(),
                    currentPage = 1,
                    totalPages = 1,
                    earliestLocalDate = null,
                ),
            )

        assertEquals(0.85f, result.yesterdayStrainRatio!!, 0.001f)
        assertEquals(75f, result.yesterdayReadiness!!, 0.001f)
    }

    @Test
    fun `buildWorkoutsState computes everyday-HR todayStrainIncrease using scoringCalculator decay diff`() {
        val prefs = UserPreferences(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE)
        val summaries =
            listOf(
                DailySummary(date = selectedDate.minusDays(10), trimpEverydayHr = 10f),
                DailySummary(date = selectedDate, trimpEverydayHr = 20f),
            )

        every { scoringCalculator.computeCtlEmaSeries(any(), any(), any()) } returns mapOf(selectedDate to 10f)
        every { scoringCalculator.computeAtlEmaSeries(any(), any(), any()) } returns mapOf(selectedDate to 15f)
        every { scoringCalculator.computeStrainRatio(15f, 10f) } returns 1.5f
        every { scoringCalculator.computeStrainRatio(12f, 10f) } returns 1.2f
        every { scoringCalculator.computeAtlEmaWithDecay(match { it[selectedDate] == 0f }, selectedDate) } returns 12f
        every { scoringCalculator.computeCtlEmaWithDecay(match { it[selectedDate] == 0f }, selectedDate) } returns 10f

        val result =
            buildWorkoutsState(
                WorkoutsStateInputs(
                    scoringCalculator = scoringCalculator,
                    latestSummary = null,
                    trimpSummaries = summaries,
                    rasSummaries = emptyList(),
                    prefs = prefs,
                    range = TimeRange.SEVEN_DAYS,
                    selectedDate = selectedDate,
                    zoneId = zoneId,
                    recentWorkouts = emptyList(),
                    currentPage = 1,
                    totalPages = 1,
                    earliestLocalDate = selectedDate.minusDays(10),
                ),
            )

        // 1.5f - 1.2f = 0.3f
        assertEquals(0.3f, result.todayStrainIncrease!!, 0.001f)
    }

    @Test
    fun `buildWorkoutsState uses workoutOnlyGains for WORKOUT_ONLY todayStrainIncrease`() {
        val prefs = UserPreferences(strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY)
        val workoutGains = listOf(0.12f, 0.18f)

        val result =
            buildWorkoutsState(
                WorkoutsStateInputs(
                    scoringCalculator = scoringCalculator,
                    latestSummary = null,
                    trimpSummaries = emptyList(),
                    rasSummaries = emptyList(),
                    prefs = prefs,
                    range = TimeRange.SEVEN_DAYS,
                    selectedDate = selectedDate,
                    zoneId = zoneId,
                    recentWorkouts = emptyList(),
                    currentPage = 1,
                    totalPages = 1,
                    earliestLocalDate = selectedDate.minusDays(10),
                    workoutOnlyGains = workoutGains,
                ),
            )

        assertEquals(0.30f, result.todayStrainIncrease!!, 0.001f)
    }

    @Test
    fun `buildRasBreakdown returns 7 days of labels and scores`() {
        val prefs = UserPreferences(rasSourceMode = LoadSourceMode.WORKOUT_ONLY)
        val summaries =
            (0..6).map { daysAgo ->
                DailySummary(
                    date = selectedDate.minusDays(daysAgo.toLong()),
                    rasWorkoutOnly = (70 + daysAgo).toFloat(),
                )
            }

        val breakdown = buildRasBreakdown(selectedDate, summaries, prefs)
        assertEquals(7, breakdown.size)
        // Earliest day (6 days back) is first, selected date is last
        assertEquals(76f, breakdown.first().second, 0.001f)
        assertEquals(70f, breakdown.last().second, 0.001f)
    }

    @Test
    fun `resolveWorkoutsRangeWindow computes correct epoch boundaries`() {
        val window = resolveWorkoutsRangeWindow(TimeRange.SEVEN_DAYS, selectedDate, zoneId)
        val expectedStart = selectedDate.minusDays(6)
        assertEquals(expectedStart, window.displayStartDate)
        assertEquals(expectedStart.atStartOfDay(zoneId).toInstant().toEpochMilli(), window.displayFromMs)
        assertEquals(
            expectedStart
                .minusDays(ScoringConstants.CHRONIC_DAYS)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli(),
            window.fetchFromMs,
        )
        assertEquals(selectedDate.atStartOfDay(zoneId).toInstant().toEpochMilli(), window.selectedMidnightMs)
        assertEquals(
            selectedDate
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli(),
            window.selectedDayEndMs,
        )
        assertEquals(
            selectedDate
                .minusDays(6)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli(),
            window.rasFromMs,
        )
    }
}
