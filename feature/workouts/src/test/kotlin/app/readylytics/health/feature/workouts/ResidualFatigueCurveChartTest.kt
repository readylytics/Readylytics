package app.readylytics.health.feature.workouts

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResidualFatigueCurveChartTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun residualFatigueSelectedPointOffset_isHiddenWithoutSelection() {
        val offset = Offset(12f, 24f)

        assertEquals(
            null,
            residualFatigueSelectedPointOffset(isSelectionVisible = false, selectedPointOffset = offset),
        )
        assertEquals(
            offset,
            residualFatigueSelectedPointOffset(isSelectionVisible = true, selectedPointOffset = offset),
        )
    }

    @Test
    fun residualFatigueChartXValues_roundToVicoPrecision() {
        val points =
            listOf(
                FatigueCurvePoint(timestampMs = 0L, timeMinutesFromStart = 0f, fatigueValue = 10f),
                FatigueCurvePoint(timestampMs = 0L, timeMinutesFromStart = 1.234567f, fatigueValue = 20f),
            )

        assertEquals(listOf(0.0, 1.2346), residualFatigueChartXValues(points))
    }

    @Test
    fun residualFatigueMaxX_shrinksOnASpringForwardDay() {
        val zone = ZoneId.of("Europe/Berlin")
        val points = listOf(pointAt(LocalDate.of(2026, 3, 29), zone))

        assertEquals(23 * 60.0, residualFatigueMaxX(points, FatigueCurveRange.ONE_DAY, zone), 0.001)
    }

    @Test
    fun residualFatigueAxisTicks_placeDayBoundariesAtRealMidnights() {
        val zone = ZoneId.of("Europe/Berlin")
        val points = listOf(pointAt(LocalDate.of(2026, 3, 28), zone))

        val ticks = residualFatigueAxisTicks(points, FatigueCurveRange.THREE_DAYS, zone)

        // 28th is 24h, 29th is 23h (spring forward), so the boundaries are 0 / 1440 / 2820 / 4260 —
        // not multiples of 1440.
        assertEquals(listOf(0.0, 1440.0, 2820.0, 4260.0), ticks.values)
        assertEquals(4260.0, residualFatigueMaxX(points, FatigueCurveRange.THREE_DAYS, zone), 0.001)
    }

    @Test
    fun residualFatigueNowMarkerX_isSetOnlyForATruncatedCurve() {
        val truncated =
            listOf(
                FatigueCurvePoint(timestampMs = 0L, timeMinutesFromStart = 0f, fatigueValue = 10f),
                FatigueCurvePoint(timestampMs = 600_000L, timeMinutesFromStart = 610f, fatigueValue = 12f),
            )
        val complete =
            listOf(
                FatigueCurvePoint(timestampMs = 0L, timeMinutesFromStart = 0f, fatigueValue = 10f),
                FatigueCurvePoint(timestampMs = 600_000L, timeMinutesFromStart = 1440f, fatigueValue = 12f),
            )

        assertEquals(610.0, residualFatigueNowMarkerX(truncated, maxX = 1440.0))
        assertEquals(null, residualFatigueNowMarkerX(complete, maxX = 1440.0))
        assertEquals(null, residualFatigueNowMarkerX(emptyList(), maxX = 1440.0))
    }

    private fun pointAt(
        date: LocalDate,
        zone: ZoneId,
    ) = FatigueCurvePoint(
        timestampMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        timeMinutesFromStart = 0f,
        fatigueValue = 0f,
    )

    @Test
    fun residualFatigueCurveChart_emptyData_displaysEmptyMessage() {
        composeRule.setContent {
            FitDashboardTheme {
                ResidualFatigueCurveChart(
                    points = emptyList(),
                    range = FatigueCurveRange.ONE_DAY,
                    isLoading = false,
                )
            }
        }

        composeRule.onNodeWithText("No residual fatigue data available for this range.").assertIsDisplayed()
    }

    @Test
    fun residualFatigueCurveChart_withPoints1D_rendersChart() {
        val points =
            listOf(
                FatigueCurvePoint(timestampMs = 0L, timeMinutesFromStart = 0f, fatigueValue = 10f),
                FatigueCurvePoint(timestampMs = 900_000L, timeMinutesFromStart = 1.234567f, fatigueValue = 25f),
                FatigueCurvePoint(timestampMs = 1_800_000L, timeMinutesFromStart = 30f, fatigueValue = 20f),
            )

        composeRule.setContent {
            FitDashboardTheme {
                ResidualFatigueCurveChart(
                    points = points,
                    range = FatigueCurveRange.ONE_DAY,
                    isLoading = false,
                )
            }
        }

        composeRule.onNodeWithTag("ResidualFatigueCurveChartCanvas").assertIsDisplayed()
    }

    @Test
    fun residualFatigueCurveChart_withPoints3DAnd7D_rendersChart() {
        val points =
            listOf(
                FatigueCurvePoint(timestampMs = 0L, timeMinutesFromStart = 0f, fatigueValue = 10f),
                FatigueCurvePoint(timestampMs = 86_400_000L, timeMinutesFromStart = 1440f, fatigueValue = 25f),
            )

        composeRule.setContent {
            FitDashboardTheme {
                ResidualFatigueCurveChart(
                    points = points,
                    range = FatigueCurveRange.THREE_DAYS,
                    isLoading = false,
                )
            }
        }

        composeRule.onNodeWithTag("ResidualFatigueCurveChartCanvas").assertIsDisplayed()
    }
}
