package app.readylytics.health.feature.workouts.mappers

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.feature.workouts.HeartRatePoint
import app.readylytics.health.feature.workouts.mappers.DailyRasBreakdownMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WorkoutMapperTest {
    @Test
    fun chartDataMapper_emptySamples_emptyChart() {
        val result = ChartDataMapper.mapToChartData(emptyList(), 0L, 60000L)
        assertEquals(emptyList<Pair<Double, Double>>(), result.first)
        assertEquals(1, result.second)
    }

    @Test
    fun chartDataMapper_singleMinuteWorkout_singleDataPoint() {
        val now = Instant.now()
        val samples =
            listOf(
                HeartRatePoint(now, 120),
                HeartRatePoint(now.plusSeconds(30), 122),
            )
        val startMs = now.minusSeconds(5).toEpochMilli()
        val endMs = now.plusSeconds(55).toEpochMilli()

        val result = ChartDataMapper.mapToChartData(samples, startMs, endMs)

        assertEquals(1, result.first.size)
        assertEquals(121.0, result.first[0].second, 0.1)
        assertEquals(1, result.second)
    }

    @Test
    fun chartDataMapper_longWorkout_correctMinuteGrouping() {
        val start = Instant.now()
        val samples = mutableListOf<HeartRatePoint>()
        for (minute in 0..59) {
            samples.add(
                HeartRatePoint(
                    start.plusSeconds((minute * 60).toLong()),
                    100 + minute,
                ),
            )
        }

        val endTime = start.plusSeconds(3600).toEpochMilli()
        val result = ChartDataMapper.mapToChartData(samples, start.toEpochMilli(), endTime)

        assertEquals(60, result.first.size)
        assertEquals(60, result.second)
    }

    @Test
    fun recoveryMetricsMapper_noEndHr_allNull() {
        val now = Instant.now()
        val samples = listOf(HeartRatePoint(now.plusSeconds(60), 80))

        val result = RecoveryMetricsMapper.mapRecoveryMetrics(samples, now.toEpochMilli(), null)

        assertNull(result.hrr1Min)
        assertNull(result.hrr2Min)
        assertNull(result.hrr3Min)
    }

    @Test
    fun recoveryMetricsMapper_noRecoverySamples_allNull() {
        val now = Instant.now()
        val samples = listOf(HeartRatePoint(now.minusSeconds(30), 120))

        val result = RecoveryMetricsMapper.mapRecoveryMetrics(samples, now.toEpochMilli(), 150)

        assertNull(result.hrr1Min)
        assertNull(result.hrr2Min)
        assertNull(result.hrr3Min)
    }

    @Test
    fun recoveryMetricsMapper_exactRecoveryMarks_correctDrops() {
        val workoutEnd = Instant.now()
        val endHr = 160
        val samples =
            listOf(
                HeartRatePoint(workoutEnd.plusSeconds(60), 142),
                HeartRatePoint(workoutEnd.plusSeconds(120), 130),
                HeartRatePoint(workoutEnd.plusSeconds(180), 120),
            )

        val result = RecoveryMetricsMapper.mapRecoveryMetrics(samples, workoutEnd.toEpochMilli(), endHr)

        assertEquals(18, result.hrr1Min)
        assertEquals(30, result.hrr2Min)
        assertEquals(40, result.hrr3Min)
    }

    @Test
    fun dailyPaiBreakdownMapper_sevenDayLookback_correctLabels() {
        val today = LocalDate.of(2026, 5, 16)
        val summaries = emptyList<DailySummary>()

        val result = DailyRasBreakdownMapper.mapDailyBreakdown(today, summaries, LoadSourceMode.WORKOUT_ONLY)

        assertEquals(7, result.size)
        assertEquals(0f, result[0].second)
        assertEquals(0f, result[6].second)
    }

    @Test
    fun dailyRasBreakdownMapper_withSummaries_populatesRasScores() {
        val today = LocalDate.of(2026, 5, 16)
        val summaries =
            listOf(
                DailySummary(date = today, rasWorkoutOnly = 85f, totalRasWorkoutOnly = 85f),
            )

        val result = DailyRasBreakdownMapper.mapDailyBreakdown(today, summaries, LoadSourceMode.WORKOUT_ONLY)

        assertEquals(7, result.size)
        assertEquals(85f, result[6].second)
    }
}
