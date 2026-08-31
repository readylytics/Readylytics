package app.readylytics.health.core.model.domain.date

import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyDateTransitionTest {
    private val testClock: Clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.now(testClock)
    private val yesterday: LocalDate = today.minusDays(1)
    private val twoDaysAgo: LocalDate = today.minusDays(2)

    @Test
    fun `NoChange returns currentDate unchanged`() {
        assertEquals(yesterday, applyDateTransition(yesterday, DateTransition.NoChange, testClock))
    }

    @Test
    fun `NoChange when at today returns today`() {
        assertEquals(today, applyDateTransition(today, DateTransition.NoChange, testClock))
    }

    @Test
    fun `UpdateTo past date returns that date`() {
        val target = today.minusDays(10)
        assertEquals(target, applyDateTransition(today, DateTransition.UpdateTo(target), testClock))
    }

    @Test
    fun `UpdateTo today returns today`() {
        assertEquals(today, applyDateTransition(yesterday, DateTransition.UpdateTo(today), testClock))
    }

    @Test
    fun `UpdateTo future date is capped to today`() {
        val future = today.plusDays(3)
        assertEquals(today, applyDateTransition(today, DateTransition.UpdateTo(future), testClock))
    }

    @Test
    fun `PreviousDay returns day before currentDate`() {
        assertEquals(yesterday, applyDateTransition(today, DateTransition.PreviousDay, testClock))
    }

    @Test
    fun `PreviousDay from arbitrary past date subtracts one day`() {
        assertEquals(twoDaysAgo, applyDateTransition(yesterday, DateTransition.PreviousDay, testClock))
    }

    @Test
    fun `NextDay when before today advances to next day`() {
        assertEquals(yesterday, applyDateTransition(twoDaysAgo, DateTransition.NextDay, testClock))
    }

    @Test
    fun `NextDay when already at today returns today`() {
        assertEquals(today, applyDateTransition(today, DateTransition.NextDay, testClock))
    }

    @Test
    fun `ResetToToday returns today regardless of currentDate`() {
        assertEquals(today, applyDateTransition(twoDaysAgo, DateTransition.ResetToToday, testClock))
    }

    @Test
    fun `ResetToToday when already at today returns today`() {
        assertEquals(today, applyDateTransition(today, DateTransition.ResetToToday, testClock))
    }
}

class IsValidFromTest {
    private val testClock: Clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC)
    private val today: LocalDate = LocalDate.now(testClock)
    private val yesterday: LocalDate = today.minusDays(1)

    @Test
    fun `NoChange is always valid`() {
        assertTrue(DateTransition.NoChange.isValidFrom(today, testClock))
        assertTrue(DateTransition.NoChange.isValidFrom(yesterday, testClock))
    }

    @Test
    fun `UpdateTo is always valid`() {
        val transition = DateTransition.UpdateTo(yesterday)
        assertTrue(transition.isValidFrom(today, testClock))
        assertTrue(transition.isValidFrom(yesterday, testClock))
    }

    @Test
    fun `PreviousDay is always valid`() {
        assertTrue(DateTransition.PreviousDay.isValidFrom(today, testClock))
        assertTrue(DateTransition.PreviousDay.isValidFrom(yesterday, testClock))
    }

    @Test
    fun `NextDay is valid when not at today`() {
        assertTrue(DateTransition.NextDay.isValidFrom(yesterday, testClock))
    }

    @Test
    fun `NextDay is invalid when already at today`() {
        assertFalse(DateTransition.NextDay.isValidFrom(today, testClock))
    }

    @Test
    fun `ResetToToday is always valid`() {
        assertTrue(DateTransition.ResetToToday.isValidFrom(today, testClock))
        assertTrue(DateTransition.ResetToToday.isValidFrom(yesterday, testClock))
    }
}
