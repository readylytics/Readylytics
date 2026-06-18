package app.readylytics.health.domain.model

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.scoring.LoadSourceMode
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadSourceSelectorTest {
    private val date = LocalDate.of(2026, 6, 2)

    // --- Selector-only regression: variant columns win over legacy columns ---

    @Test
    fun `selectTrimp returns variant value`() {
        val summary =
            DailySummary(
                date = date,
                trimpWorkoutOnly = 100f,
                trimpEverydayHr = 200f,
            )
        assertEquals(100f, LoadSourceSelector.selectTrimp(summary, LoadSourceMode.WORKOUT_ONLY))
        assertEquals(200f, LoadSourceSelector.selectTrimp(summary, LoadSourceMode.EVERYDAY_HEART_RATE))
    }

    @Test
    fun `selectAtl returns variant value`() {
        val summary =
            DailySummary(
                date = date,
                atlWorkoutOnly = 10f,
                atlEverydayHr = 20f,
            )
        assertEquals(10f, LoadSourceSelector.selectAtl(summary, LoadSourceMode.WORKOUT_ONLY))
        assertEquals(20f, LoadSourceSelector.selectAtl(summary, LoadSourceMode.EVERYDAY_HEART_RATE))
    }

    @Test
    fun `selectCtl returns variant value`() {
        val summary =
            DailySummary(
                date = date,
                ctlWorkoutOnly = 30f,
                ctlEverydayHr = 40f,
            )
        assertEquals(30f, LoadSourceSelector.selectCtl(summary, LoadSourceMode.WORKOUT_ONLY))
        assertEquals(40f, LoadSourceSelector.selectCtl(summary, LoadSourceMode.EVERYDAY_HEART_RATE))
    }

    @Test
    fun `selectStrainRatio returns variant value, never the legacy strainRatio value`() {
        val summary =
            DailySummary(
                date = date,
                strainRatioWorkoutOnly = 0.5f,
                strainRatioEverydayHr = 0.8f,
            )
        assertEquals(0.5f, LoadSourceSelector.selectStrainRatio(summary, LoadSourceMode.WORKOUT_ONLY))
        assertEquals(0.8f, LoadSourceSelector.selectStrainRatio(summary, LoadSourceMode.EVERYDAY_HEART_RATE))
    }

    @Test
    fun `selectLoadScore returns variant value, never the legacy loadScore value`() {
        val summary =
            DailySummary(
                date = date,
                loadScoreWorkoutOnly = 50f,
                loadScoreEverydayHr = 60f,
            )
        assertEquals(50f, LoadSourceSelector.selectLoadScore(summary, LoadSourceMode.WORKOUT_ONLY))
        assertEquals(60f, LoadSourceSelector.selectLoadScore(summary, LoadSourceMode.EVERYDAY_HEART_RATE))
    }

    @Test
    fun `selectReadiness returns variant value, never the legacy readinessScore value`() {
        val summary =
            DailySummary(
                date = date,
                readinessWorkoutOnly = 70f,
                readinessEverydayHr = 80f,
            )
        assertEquals(70f, LoadSourceSelector.selectReadiness(summary, LoadSourceMode.WORKOUT_ONLY))
        assertEquals(80f, LoadSourceSelector.selectReadiness(summary, LoadSourceMode.EVERYDAY_HEART_RATE))
    }

    // --- Toggle test: same summary, prefs differ only in mode ---

    @Test
    fun `toMetrics output differs when strainLoadSourceMode and rasSourceMode toggle`() {
        val summary =
            DailySummary(
                date = date,
                trimpWorkoutOnly = 100f,
                trimpEverydayHr = 200f,
                loadScoreWorkoutOnly = 50f,
                loadScoreEverydayHr = 60f,
                readinessWorkoutOnly = 70f,
                readinessEverydayHr = 80f,
                strainRatioWorkoutOnly = 0.5f,
                strainRatioEverydayHr = 0.8f,
                totalRasWorkoutOnly = 70f,
                totalRasEverydayHr = 75f,
                rasWorkoutOnly = 5f,
                rasEverydayHr = 6f,
            )

        val workoutOnlyPrefs =
            UserPreferences(
                strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY,
                rasSourceMode = LoadSourceMode.WORKOUT_ONLY,
            )
        val everydayHrPrefs =
            UserPreferences(
                strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
                rasSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
            )

        val workoutOnlyMetrics = DailyMetricsMapper.toMetrics(summary, workoutOnlyPrefs)
        val everydayHrMetrics = DailyMetricsMapper.toMetrics(summary, everydayHrPrefs)

        assertEquals(100, workoutOnlyMetrics.trimpRounded)
        assertEquals(200, everydayHrMetrics.trimpRounded)

        assertEquals(50, workoutOnlyMetrics.loadScoreRounded)
        assertEquals(60, everydayHrMetrics.loadScoreRounded)

        assertEquals(70, workoutOnlyMetrics.readinessRounded)
        assertEquals(80, everydayHrMetrics.readinessRounded)

        assertEquals("0.50", workoutOnlyMetrics.strainRatioDisplay)
        assertEquals("0.80", everydayHrMetrics.strainRatioDisplay)

        assertEquals(70, workoutOnlyMetrics.rasRounded)
        assertEquals(75, everydayHrMetrics.rasRounded)

        assertEquals(5, workoutOnlyMetrics.rasDayScoreRounded)
        assertEquals(6, everydayHrMetrics.rasDayScoreRounded)
    }

    // --- readinessLowConfidence matrix ---

    @Test
    fun `readinessLowConfidence is true only for EVERYDAY_HEART_RATE with NONE LOW or MEDIUM confidence`() {
        val lowConfidenceValues = listOf("NONE", "LOW", "MEDIUM")
        val highOrUnknownValues = listOf("HIGH", null)

        for (confidence in lowConfidenceValues) {
            val summary = DailySummary(date = date, everydayLoadConfidence = confidence)
            val prefs = UserPreferences(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE)
            assertTrue(
                LoadSourceSelector.readinessLowConfidence(summary, prefs),
                "Expected low confidence for EVERYDAY_HEART_RATE + $confidence",
            )

            val workoutOnlyPrefs = UserPreferences(strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY)
            assertFalse(
                LoadSourceSelector.readinessLowConfidence(summary, workoutOnlyPrefs),
                "Expected false for WORKOUT_ONLY + $confidence",
            )
        }

        for (confidence in highOrUnknownValues) {
            val summary = DailySummary(date = date, everydayLoadConfidence = confidence)
            val prefs = UserPreferences(strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE)
            assertFalse(
                LoadSourceSelector.readinessLowConfidence(summary, prefs),
                "Expected false for EVERYDAY_HEART_RATE + $confidence",
            )

            val workoutOnlyPrefs = UserPreferences(strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY)
            assertFalse(
                LoadSourceSelector.readinessLowConfidence(summary, workoutOnlyPrefs),
                "Expected false for WORKOUT_ONLY + $confidence",
            )
        }
    }

    // --- needsRecalc ---

    @Test
    fun `needsRecalc is true when EVERYDAY_HEART_RATE strain variants are null, false when populated`() {
        val notYetComputed = DailySummary(date = date)
        val computed =
            DailySummary(
                date = date,
                trimpEverydayHr = 200f,
                loadScoreEverydayHr = 60f,
            )
        val everydayHrPrefs =
            UserPreferences(
                strainLoadSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
                rasSourceMode = LoadSourceMode.WORKOUT_ONLY,
            )

        assertTrue(LoadSourceSelector.needsRecalc(notYetComputed, everydayHrPrefs))
        assertFalse(LoadSourceSelector.needsRecalc(computed, everydayHrPrefs))
    }

    @Test
    fun `needsRecalc is true when EVERYDAY_HEART_RATE ras variants are null, false when populated`() {
        val notYetComputed = DailySummary(date = date)
        val computed =
            DailySummary(
                date = date,
                rasEverydayHr = 6f,
                totalRasEverydayHr = 75f,
            )
        val everydayHrPrefs =
            UserPreferences(
                strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY,
                rasSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
            )

        assertTrue(LoadSourceSelector.needsRecalc(notYetComputed, everydayHrPrefs))
        assertFalse(LoadSourceSelector.needsRecalc(computed, everydayHrPrefs))
    }

    @Test
    fun `needsRecalc is false for WORKOUT_ONLY modes regardless of everyday-HR column state`() {
        val summary = DailySummary(date = date)
        val workoutOnlyPrefs =
            UserPreferences(
                strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY,
                rasSourceMode = LoadSourceMode.WORKOUT_ONLY,
            )
        assertFalse(LoadSourceSelector.needsRecalc(summary, workoutOnlyPrefs))
    }
}
