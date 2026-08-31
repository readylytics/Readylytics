package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.repository.FatigueWorkoutInput
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class GenerateResidualFatigueCurveUseCaseTest {

    private val useCase = GenerateResidualFatigueCurveUseCase()

    @Test
    fun `execute samples 96 quarter-hour grid points across full 24h day`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val workouts = emptyList<FatigueWorkoutInput>()

        val curve = useCase.execute(date, date, zone, config, workouts)
        assertEquals(96, curve.size)
        assertEquals(0f, curve.first().timeMinutesFromStart, 0.01f)
        assertEquals(23 * 60 + 45f, curve.last().timeMinutesFromStart, 0.01f)
        curve.forEach { assertEquals(0f, it.fatigueValue, 0.001f) }
    }

    @Test
    fun `execute samples 96 points per day across 3-day range`() {
        val startDate = LocalDate.of(2026, 8, 27)
        val endDate = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val workouts = emptyList<FatigueWorkoutInput>()

        val curve = useCase.execute(startDate, endDate, zone, config, workouts)
        // 3 days * 96 points/day = 288 points
        assertEquals(288, curve.size)
        assertEquals(0f, curve.first().timeMinutesFromStart, 0.01f)
        assertEquals((3 * 24 * 60 - 15).toFloat(), curve.last().timeMinutesFromStart, 0.01f)
    }

    @Test
    fun `execute samples 672 points across 7-day range with workout spikes across multiple days`() {
        val startDate = LocalDate.of(2026, 8, 23)
        val endDate = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val workout1 = FatigueWorkoutInput("w1", startMs + 36 * 3600 * 1000L + 7 * 60 * 1000L, 50f) // Day 2 12:07
        val workout2 = FatigueWorkoutInput("w2", startMs + 100 * 3600 * 1000L + 13 * 60 * 1000L, 40f) // Day 5 04:13

        val curve = useCase.execute(startDate, endDate, zone, config, listOf(workout1, workout2))
        // 7 * 96 = 672 grid points + 2 exact timestamps = 674 points
        assertEquals(674, curve.size)
    }

    @Test
    fun `execute inserts exact workout end timestamps and captures spike and decay`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val workoutEndMs = dayStartMs + 10 * 3600 * 1000L + 7 * 60 * 1000L // 10:07 AM
        val workouts = listOf(FatigueWorkoutInput("w1", workoutEndMs, 50f))

        val curve = useCase.execute(date, date, zone, config, workouts)
        // 96 grid points + 1 exact timestamp = 97 points
        assertEquals(97, curve.size)
        val pointAtWorkout = curve.first { it.timestampMs == workoutEndMs }
        assertEquals(607f, pointAtWorkout.timeMinutesFromStart, 0.01f)
        assertEquals(50f, pointAtWorkout.fatigueValue, 0.01f)

        // 24 hours after workout end, value is decayed by 50%
        val exactly24hLaterMs = workoutEndMs + 24 * 3600 * 1000L
        val decayed = useCase.evaluateAt(exactly24hLaterMs, config, workouts)
        assertEquals(25f, decayed, 0.01f)
    }

    @Test
    fun `execute preserves strict chronological order of sample points`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val w1 = FatigueWorkoutInput("w1", dayStartMs + 37 * 60 * 1000L, 40f) // 00:37
        val w2 = FatigueWorkoutInput("w2", dayStartMs + 15 * 60 * 1000L, 30f) // exact grid point (00:15)
        val workouts = listOf(w1, w2)

        val curve = useCase.execute(date, date, zone, config, workouts)
        // 96 grid points + 1 non-grid timestamp (00:37) = 97 points (00:15 deduplicated in TreeSet)
        assertEquals(97, curve.size)

        for (i in 0 until curve.size - 1) {
            assertTrue(
                "Timestamps must be strictly ascending",
                curve[i].timestampMs < curve[i + 1].timestampMs,
            )
            assertTrue(
                "Minutes from start must be strictly ascending",
                curve[i].timeMinutesFromStart < curve[i + 1].timeMinutesFromStart,
            )
        }
    }

    @Test
    fun `execute accounts for prior day workouts decaying into selected day`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        // Workout finished 24 hours before selected day start
        val priorWorkoutEndMs = dayStartMs - 24 * 3600 * 1000L
        val workouts = listOf(FatigueWorkoutInput("w_prior", priorWorkoutEndMs, 100f))

        val curve = useCase.execute(date, date, zone, config, workouts)
        assertEquals(96, curve.size)
        // At 00:00 (start of selected day, 24h after workout), fatigue should be 50
        assertEquals(50f, curve.first().fatigueValue, 0.01f)
        // At 24h mark of selected day (48h after workout), fatigue should be 25
        val endOfDayFatigue = useCase.evaluateAt(dayStartMs + 24 * 3600 * 1000L, config, workouts)
        assertEquals(25f, endOfDayFatigue, 0.01f)
    }

    @Test
    fun `execute steps the grid in the zone so a spring-forward day yields 92 points`() {
        // Europe/Berlin loses an hour at 02:00 on 2026-03-29: the day is 23 hours long.
        val date = LocalDate.of(2026, 3, 29)
        val zone = ZoneId.of("Europe/Berlin")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)

        val curve = useCase.execute(date, date, zone, config, emptyList())

        assertEquals(23 * 4, curve.size)
        val rangeEndMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(
            "No sample may fall on or after the range end",
            curve.all { it.timestampMs < rangeEndMs },
        )
        assertEquals((23 * 60 - 15).toFloat(), curve.last().timeMinutesFromStart, 0.01f)
    }

    @Test
    fun `execute steps the grid in the zone so a fall-back day yields 100 points`() {
        // Europe/Berlin repeats 02:00-03:00 on 2026-10-25: the day is 25 hours long.
        val date = LocalDate.of(2026, 10, 25)
        val zone = ZoneId.of("Europe/Berlin")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)

        val curve = useCase.execute(date, date, zone, config, emptyList())

        assertEquals(25 * 4, curve.size)
        assertEquals((25 * 60 - 15).toFloat(), curve.last().timeMinutesFromStart, 0.01f)
    }

    @Test
    fun `execute truncates the curve at now and ends exactly on it`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMs = dayStartMs + 10 * 3600 * 1000L + 7 * 60 * 1000L // 10:07

        val curve = useCase.execute(date, date, zone, config, emptyList(), nowMs = nowMs)

        // Grid points 00:00..10:00 inclusive (41), plus the exact now sample.
        assertEquals(42, curve.size)
        assertEquals(nowMs, curve.last().timestampMs)
        assertTrue("Nothing may be plotted after now", curve.all { it.timestampMs <= nowMs })
    }

    @Test
    fun `execute ignores a now bound that is after the range end`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val nowMs = date.plusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()

        val curve = useCase.execute(date, date, zone, config, emptyList(), nowMs = nowMs)

        assertEquals(96, curve.size)
    }

    @Test
    fun `execute drops workout impulses that have not happened yet`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val nowMs = dayStartMs + 6 * 3600 * 1000L
        val futureWorkout = FatigueWorkoutInput("w_future", dayStartMs + 20 * 3600 * 1000L, 80f)

        val curve = useCase.execute(date, date, zone, config, listOf(futureWorkout), nowMs = nowMs)

        assertTrue("A future workout must not add a sample", curve.none { it.timestampMs > nowMs })
        curve.forEach { assertEquals(0f, it.fatigueValue, 0.0001f) }
    }

    @Test
    fun `execute single-pass accumulation matches direct summation`() {
        val startDate = LocalDate.of(2026, 8, 23)
        val endDate = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 18f, fatigueGain = 1.4f)
        val startMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val workouts =
            listOf(
                FatigueWorkoutInput("w1", startMs + 5 * 3600 * 1000L, 55f),
                FatigueWorkoutInput("w2", startMs + 37 * 3600 * 1000L + 11 * 60 * 1000L, 90f),
                FatigueWorkoutInput("w3", startMs + 121 * 3600 * 1000L, 20f),
            )

        val curve = useCase.execute(startDate, endDate, zone, config, workouts)

        curve.forEach { point ->
            assertEquals(
                "Accumulated value must match the direct sum at ${point.timestampMs}",
                useCase.evaluateAt(point.timestampMs, config, workouts),
                point.fatigueValue,
                0.01f,
            )
        }
    }

    @Test
    fun `execute when disabled returns all zeroes`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(enabled = false, halfLifeHours = 24f, fatigueGain = 1f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val workouts = listOf(FatigueWorkoutInput("w1", dayStartMs + 3600 * 1000L + 7 * 60 * 1000L, 50f))

        val curve = useCase.execute(date, date, zone, config, workouts)
        assertEquals(97, curve.size)
        curve.forEach { assertEquals(0f, it.fatigueValue, 0.0001f) }
        assertEquals(0f, useCase.evaluateAt(dayStartMs + 3600 * 1000L, config, workouts), 0.0001f)
    }

    @Test
    fun `execute ignores workouts with non-positive trimp`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("UTC")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val workouts = listOf(
            FatigueWorkoutInput("w_zero", dayStartMs + 3600 * 1000L, 0f),
            FatigueWorkoutInput("w_neg", dayStartMs + 7200 * 1000L, -10f),
        )

        val curve = useCase.execute(date, date, zone, config, workouts)
        curve.forEach { assertEquals(0f, it.fatigueValue, 0.0001f) }
    }

    @Test
    fun `execute works consistently in non-UTC timezone`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("America/New_York")
        val config = ResidualFatigueConfig(halfLifeHours = 24f, fatigueGain = 1.5f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val workoutEndMs = dayStartMs + 8 * 3600 * 1000L // 08:00 AM EDT
        val workouts = listOf(FatigueWorkoutInput("w_ny", workoutEndMs, 40f))

        val curve = useCase.execute(date, date, zone, config, workouts)
        assertEquals(96, curve.size)

        // Point before workout: 07:45 (index 31) -> 0 fatigue
        val pointBefore = curve[31]
        assertEquals(7 * 60 + 45f, pointBefore.timeMinutesFromStart, 0.01f)
        assertEquals(0f, pointBefore.fatigueValue, 0.001f)

        // Point at workout: 08:00 (index 32) -> 40 * 1.5 = 60 fatigue
        val pointAt = curve[32]
        assertEquals(8 * 60f, pointAt.timeMinutesFromStart, 0.01f)
        assertEquals(60f, pointAt.fatigueValue, 0.01f)
    }

    @Test
    fun `curve points match evaluateAt results exactly`() {
        val date = LocalDate.of(2026, 8, 29)
        val zone = ZoneId.of("Europe/Berlin")
        val config = ResidualFatigueConfig(halfLifeHours = 36f, fatigueGain = 1.2f)
        val dayStartMs = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val w1 = FatigueWorkoutInput("w1", dayStartMs - 12 * 3600 * 1000L, 80f)
        val w2 = FatigueWorkoutInput("w2", dayStartMs + 9 * 3600 * 1000L + 23 * 60 * 1000L, 60f)
        val workouts = listOf(w1, w2)

        val curve = useCase.execute(date, date, zone, config, workouts)
        for (point in curve) {
            val eval = useCase.evaluateAt(point.timestampMs, config, workouts)
            assertEquals(eval, point.fatigueValue, 0.0001f)
        }
    }
}
