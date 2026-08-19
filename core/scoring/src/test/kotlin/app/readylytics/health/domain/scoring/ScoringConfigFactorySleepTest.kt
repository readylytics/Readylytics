package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.UserPreferences
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScoringConfigFactorySleepTest {
    private val factory = ScoringConfigFactory()
    private val today = LocalDate.of(2026, 1, 10)

    private fun build(prefs: UserPreferences) =
        factory.build(userPreferences = prefs, installDate = today.minusDays(60), currentDate = today)

    @Test
    fun `weight profile and onset ratio flow into the config`() {
        val config =
            build(
                UserPreferences(
                    sleepScoreWeightProfile = SleepScoreWeightProfile.LIGHT_SLEEPER,
                    hypersomniaOnsetPercent = 110,
                ),
            )

        assertEquals(SleepScoreWeightProfile.LIGHT_SLEEPER, config.sleepWeightProfile)
        assertEquals(1.10f, config.hypersomniaOnsetRatio, 0.0001f)
    }

    @Test
    fun `changing sleep score settings changes the config hash`() {
        val balanced = build(UserPreferences(sleepScoreWeightProfile = SleepScoreWeightProfile.BALANCED))
        val lightSleeper = build(UserPreferences(sleepScoreWeightProfile = SleepScoreWeightProfile.LIGHT_SLEEPER))
        val earlierOnset = build(UserPreferences(hypersomniaOnsetPercent = 105))

        assertNotEquals(balanced.auditTrail.configHashCode, lightSleeper.auditTrail.configHashCode)
        assertNotEquals(balanced.auditTrail.configHashCode, earlierOnset.auditTrail.configHashCode)
    }
}
