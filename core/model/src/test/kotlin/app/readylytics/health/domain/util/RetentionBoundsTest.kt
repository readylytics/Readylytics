package app.readylytics.health.domain.util

import app.readylytics.health.data.preferences.UserPreferences
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RetentionBoundsTest {
    private val today = LocalDate.of(2026, 6, 5)

    @Test
    fun `enabled retention resolves start date to today minus retentionDays`() {
        val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 365)
        assertEquals(today.minusDays(365), RetentionBounds.resolveResyncStartDate(prefs, today))
    }

    @Test
    fun `disabled retention falls back to the absolute max window`() {
        val prefs = UserPreferences(retentionDaysEnabled = false, retentionDays = 365)
        assertEquals(
            today.minusDays(RetentionBounds.ABSOLUTE_MAX_DAYS),
            RetentionBounds.resolveResyncStartDate(prefs, today),
        )
    }

    @Test
    fun `enabled retention produces a non-null cutoff at the retention boundary`() {
        val prefs = UserPreferences(retentionDaysEnabled = true, retentionDays = 180)
        val expected =
            today
                .minusDays(180)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        assertEquals(expected, RetentionBounds.resolveRetentionCutoffMs(prefs, today))
    }

    @Test
    fun `disabled retention produces a null cutoff (keep everything)`() {
        val prefs = UserPreferences(retentionDaysEnabled = false, retentionDays = 365)
        assertNull(RetentionBounds.resolveRetentionCutoffMs(prefs, today))
    }
}
