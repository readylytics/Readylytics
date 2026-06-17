package app.readylytics.health.ui.dashboard

import app.readylytics.health.R
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.GetDashboardDataUseCase
import app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.getOrThrow
import app.readylytics.health.domain.util.ResourceProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DashboardBaselineDeltaTextTest {
    private val resourceProvider = FakeResourceProvider()
    private val useCase =
        GetDashboardDataUseCase(
            resourceProvider = resourceProvider,
            getWorkoutMetricsUseCase = GetWorkoutMetricsUseCase(resourceProvider),
        )

    @Test
    fun restingHrCardUsesExistingBaselineDiffAndArrowForChipText() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 6, 17),
                restingHeartRate = 48,
                rhrBpm = 51f,
                isCalibrating = false,
            )

        val result =
            useCase(
                summary = summary,
                prefs = UserPreferences(),
                date = summary.date,
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )

        val card = result.getOrThrow().cardDataMap[CardId.RESTING_HR]!!
        assertEquals("↓ 3 bpm vs baseline", card.baselineDeltaText)
        assertEquals(BaselineDeltaDirection.DOWN, card.baselineDeltaDirection)
    }

    @Test
    fun hrvCardHasNoChipWhenBaselineMissing() {
        val summary =
            DailySummary(
                date = LocalDate.of(2026, 6, 17),
                nocturnalHrv = 62,
                isCalibrating = false,
            )

        val result =
            useCase(
                summary = summary,
                prefs = UserPreferences(),
                date = summary.date,
                lastSleepSession = null,
                rasSummaries = emptyList(),
            )

        val card = result.getOrThrow().cardDataMap[CardId.HRV]!!
        assertNull(card.baselineDeltaText)
        assertNull(card.baselineDeltaDirection)
    }

    private class FakeResourceProvider : ResourceProvider {
        override fun getString(resId: Int): String =
            when (resId) {
                R.string.unit_bpm -> "bpm"
                R.string.unit_ms -> "ms"
                R.string.metric_baseline_delta_format -> "%1\$s %2\$d %3\$s vs baseline"
                R.string.card_title_resting_hr -> "Resting heart rate"
                R.string.card_title_hrv -> "HRV"
                R.string.card_title_sleep_rhr -> "Sleep RHR"
                R.string.card_title_sleep_score -> "Sleep score"
                R.string.card_title_readiness -> "Readiness"
                R.string.card_title_ras -> "RAS"
                R.string.card_title_sleep_duration -> "Sleep duration"
                R.string.card_title_sleep_efficiency -> "Sleep Efficiency"
                R.string.card_title_strain_ratio -> "Strain ratio"
                R.string.card_title_weight -> "Weight"
                R.string.card_title_body_fat -> "Body Fat"
                R.string.card_title_blood_pressure -> "Blood Pressure"
                R.string.card_title_oxygen_saturation -> "Oxygen saturation"
                else -> ""
            }

        override fun getString(
            resId: Int,
            vararg formatArgs: Any,
        ): String = getString(resId).format(*formatArgs)
    }
}
