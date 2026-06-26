package app.readylytics.health.domain.date

import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyDateTransitionTest {
    private val today: LocalDate = LocalDate.now()
    private val yesterday: LocalDate = today.minusDays(1)
    private val twoDaysAgo: LocalDate = today.minusDays(2)

    @Test
    fun `NoChange returns currentDate unchanged`() {
        assertEquals(yesterday, applyDateTransition(yesterday, DateTransition.NoChange))
    }

    @Test
    fun `NoChange when at today returns today`() {
        assertEquals(today, applyDateTransition(today, DateTransition.NoChange))
    }

    @Test
    fun `UpdateTo past date returns that date`() {
        val target = today.minusDays(10)
        assertEquals(target, applyDateTransition(today, DateTransition.UpdateTo(target)))
    }

    @Test
    fun `UpdateTo today returns today`() {
        assertEquals(today, applyDateTransition(yesterday, DateTransition.UpdateTo(today)))
    }

    @Test
    fun `UpdateTo future date is capped to today`() {
        val future = today.plusDays(3)
        assertEquals(today, applyDateTransition(today, DateTransition.UpdateTo(future)))
    }

    @Test
    fun `PreviousDay returns day before currentDate`() {
        assertEquals(yesterday, applyDateTransition(today, DateTransition.PreviousDay))
    }

    @Test
    fun `PreviousDay from arbitrary past date subtracts one day`() {
        assertEquals(twoDaysAgo, applyDateTransition(yesterday, DateTransition.PreviousDay))
    }

    @Test
    fun `NextDay when before today advances to next day`() {
        assertEquals(yesterday, applyDateTransition(twoDaysAgo, DateTransition.NextDay))
    }

    @Test
    fun `NextDay when already at today returns today`() {
        assertEquals(today, applyDateTransition(today, DateTransition.NextDay))
    }

    @Test
    fun `ResetToToday returns today regardless of currentDate`() {
        assertEquals(today, applyDateTransition(twoDaysAgo, DateTransition.ResetToToday))
    }

    @Test
    fun `ResetToToday when already at today returns today`() {
        assertEquals(today, applyDateTransition(today, DateTransition.ResetToToday))
    }
}

class IsValidFromTest {
    private val today: LocalDate = LocalDate.now()
    private val yesterday: LocalDate = today.minusDays(1)

    @Test
    fun `NoChange is always valid`() {
        assertTrue(DateTransition.NoChange.isValidFrom(today))
        assertTrue(DateTransition.NoChange.isValidFrom(yesterday))
    }

    @Test
    fun `UpdateTo is always valid`() {
        val transition = DateTransition.UpdateTo(yesterday)
        assertTrue(transition.isValidFrom(today))
        assertTrue(transition.isValidFrom(yesterday))
    }

    @Test
    fun `PreviousDay is always valid`() {
        assertTrue(DateTransition.PreviousDay.isValidFrom(today))
        assertTrue(DateTransition.PreviousDay.isValidFrom(yesterday))
    }

    @Test
    fun `NextDay is valid when not at today`() {
        assertTrue(DateTransition.NextDay.isValidFrom(yesterday))
    }

    @Test
    fun `NextDay is invalid when already at today`() {
        assertFalse(DateTransition.NextDay.isValidFrom(today))
    }

    @Test
    fun `ResetToToday is always valid`() {
        assertTrue(DateTransition.ResetToToday.isValidFrom(today))
        assertTrue(DateTransition.ResetToToday.isValidFrom(yesterday))
    }
}
