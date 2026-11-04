package app.readylytics.health.core.scoring.domain.scoring.strategies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RasScoringStrategyEwmaTest {
    private val strategy = RasScoringStrategy()
    private val delta = 0.001f

    @Test
    fun `empty map returns 0 for all requested series dates`() {
        val start = LocalDate.of(2026, 1, 1)
        val end = LocalDate.of(2026, 1, 5)
        val series = strategy.computeCtlEmaSeries(emptyMap(), start, end)

        assertEquals(5, series.size)
        series.values.forEach { assertEquals(0f, it, delta) }

        val pointValue = strategy.computeCtlEmaWithDecay(emptyMap(), end)
        assertEquals(0f, pointValue, delta)
    }

    @Test
    fun `sparse map and explicit zero map produce identical EWMA`() {
        val today = LocalDate.of(2026, 3, 1)
        val sparseMap = mapOf(
            today.minusDays(60) to 100f,
            today.minusDays(30) to 80f,
            today to 50f,
        )

        val explicitZeroMap = mutableMapOf<LocalDate, Float>()
        var d = today.minusDays(70)
        while (!d.isAfter(today)) {
            explicitZeroMap[d] = sparseMap[d] ?: 0f
            d = d.plusDays(1)
        }

        val ctlSparse = strategy.computeCtlEmaWithDecay(sparseMap, today)
        val ctlExplicit = strategy.computeCtlEmaWithDecay(explicitZeroMap, today)
        assertEquals(ctlExplicit, ctlSparse, delta)

        val atlSparse = strategy.computeAtlEmaWithDecay(sparseMap, today)
        val atlExplicit = strategy.computeAtlEmaWithDecay(explicitZeroMap, today)
        assertEquals(atlExplicit, atlSparse, delta)

        val seriesSparse = strategy.computeCtlEmaSeries(sparseMap, today.minusDays(7), today)
        val seriesExplicit = strategy.computeCtlEmaSeries(explicitZeroMap, today.minusDays(7), today)
        assertEquals(seriesExplicit, seriesSparse)
    }

    @Test
    fun `single workout does not create size 1 cliff`() {
        val day1 = LocalDate.of(2026, 1, 1)
        val day2 = LocalDate.of(2026, 1, 2)
        val trimpMapDay1 = mapOf(day1 to 100f)
        val trimpMapDay2 = mapOf(day1 to 100f, day2 to 0f)

        // Day 1: 100 TRIMP with alpha = 2 / (7 + 1) = 0.25 -> 25.0
        val atlDay1 = strategy.computeAtlEmaWithDecay(trimpMapDay1, day1)
        assertEquals(25.0f, atlDay1, delta)

        // Day 1: 100 TRIMP with alpha = 2 / (42 + 1) = 2/43 -> ~4.651
        val ctlDay1 = strategy.computeCtlEmaWithDecay(trimpMapDay1, day1)
        assertEquals(100f * (2f / 43f), ctlDay1, delta)

        // Day 2 (0 TRIMP): ATL decays smoothly from 25.0 to 18.75
        val atlDay2 = strategy.computeAtlEmaWithDecay(trimpMapDay2, day2)
        assertEquals(25.0f * 0.75f, atlDay2, delta)

        // Day 2 (0 TRIMP): CTL decays smoothly
        val ctlDay2 = strategy.computeCtlEmaWithDecay(trimpMapDay2, day2)
        assertEquals(ctlDay1 * (41f / 43f), ctlDay2, delta)

        // Verify continuity: smooth exponential decay without sudden jumps
        assertTrue(atlDay2 < atlDay1)
        assertTrue(ctlDay2 < ctlDay1)
    }

    @Test
    fun `point evaluation equals series evaluation at rangeEnd`() {
        val today = LocalDate.of(2026, 4, 15)
        val trimpMap = mapOf(
            today.minusDays(40) to 120f,
            today.minusDays(20) to 80f,
            today.minusDays(5) to 60f,
            today to 45f,
        )

        val seriesStart = today.minusDays(6)
        val ctlSeries = strategy.computeCtlEmaSeries(trimpMap, seriesStart, today)
        val ctlPoint = strategy.computeCtlEmaWithDecay(trimpMap, today)
        assertEquals(ctlPoint, ctlSeries[today] ?: -1f, delta)

        val atlSeries = strategy.computeAtlEmaSeries(trimpMap, seriesStart, today)
        val atlPoint = strategy.computeAtlEmaWithDecay(trimpMap, today)
        assertEquals(atlPoint, atlSeries[today] ?: -1f, delta)
    }

    @Test
    fun `value is independent of data older than the lookback anchor`() {
        val today = LocalDate.of(2026, 6, 1)
        val recent = mapOf(today.minusDays(30) to 90f, today to 40f)
        val withAncient = recent + mapOf(today.minusDays(400) to 500f, today.minusDays(200) to 300f)

        assertEquals(
            strategy.computeCtlEmaWithDecay(recent, today),
            strategy.computeCtlEmaWithDecay(withAncient, today),
            0f,
        )
        assertEquals(
            strategy.computeAtlEmaWithDecay(recent, today),
            strategy.computeAtlEmaWithDecay(withAncient, today),
            0f,
        )
    }

    @Test
    fun `series value for a date equals a standalone point evaluation of that date`() {
        val today = LocalDate.of(2026, 6, 1)
        val trimp = mapOf(today.minusDays(100) to 200f, today.minusDays(50) to 70f, today.minusDays(3) to 55f)

        val series = strategy.computeCtlEmaSeries(trimp, today.minusDays(6), today)

        series.forEach { (date, value) ->
            assertEquals(strategy.computeCtlEmaWithDecay(trimp, date), value, 0f)
        }
    }
}
