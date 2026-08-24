package app.readylytics.health.data.backup

import app.readylytics.health.core.model.data.preferences.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupPreferencesBuilderBirthDateTest {
    private val emptyLayouts =
        BackupLayoutSnapshots(
            dashboardCards = null,
            vitalsCards = null,
            vitalsCharts = null,
            sleepTopCards = null,
            sleepCharts = null,
            sleepMetricCards = null,
            workoutCards = null,
            workoutCharts = null,
            workoutHistory = null,
            workoutDetailLayouts = null,
        )

    @Test
    fun `valid birth date splits into day, month, year`() {
        val result = buildUserPreferencesBackup(UserPreferences(birthDate = "1990-07-15"), emptyLayouts)
        assertEquals(15, result.birthDay)
        assertEquals(7, result.birthMonth)
        assertEquals(1990, result.birthYear)
    }

    @Test
    fun `null birth date yields null day, month, year`() {
        val result = buildUserPreferencesBackup(UserPreferences(birthDate = null), emptyLayouts)
        assertNull(result.birthDay)
        assertNull(result.birthMonth)
        assertNull(result.birthYear)
    }

    @Test
    fun `malformed birth date yields null day, month, year`() {
        val result = buildUserPreferencesBackup(UserPreferences(birthDate = "not-a-date"), emptyLayouts)
        assertNull(result.birthDay)
        assertNull(result.birthMonth)
        assertNull(result.birthYear)
    }
}
