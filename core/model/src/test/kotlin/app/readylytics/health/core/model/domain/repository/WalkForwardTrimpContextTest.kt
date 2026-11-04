package app.readylytics.health.core.model.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.TreeMap

class WalkForwardTrimpContextTest {
    private val day = LocalDate.of(2026, 6, 1)

    private fun context(trackingStart: LocalDate?) =
        WalkForwardTrimpContext(TreeMap(), TreeMap(), trackingStart)

    @Test
    fun `registering a day before the snapshot lowers the tracking start`() {
        val ctx = context(day)

        ctx.registerTrackedDay(day.minusDays(30))

        assertEquals(day.minusDays(30), ctx.trackingStartDate)
    }

    @Test
    fun `registering a later day keeps the earlier tracking start`() {
        val ctx = context(day)

        ctx.registerTrackedDay(day.plusDays(5))

        assertEquals(day, ctx.trackingStartDate)
    }

    @Test
    fun `registering into an empty context establishes the tracking start`() {
        val ctx = context(null)
        assertNull(ctx.trackingStartDate)

        ctx.registerTrackedDay(day)

        assertEquals(day, ctx.trackingStartDate)
    }

    @Test
    fun `backfill walk-forward converges to the same tracking start as a second run`() {
        // First resync: snapshot = existing first row (today-30); walk covers today-60..today-30.
        val firstRun = context(day.minusDays(30))
        (60L downTo 30L).forEach { firstRun.registerTrackedDay(day.minusDays(it)) }
        // Second resync: the DB now reports the backfilled first row as the snapshot.
        val secondRun = context(day.minusDays(60))
        (60L downTo 30L).forEach { secondRun.registerTrackedDay(day.minusDays(it)) }

        assertEquals(secondRun.trackingStartDate, firstRun.trackingStartDate)
    }
}
