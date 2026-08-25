package app.readylytics.health.core.ui.components

import androidx.compose.ui.graphics.Color
import app.readylytics.health.core.ui.common.PeriodAverageSummary

/**
 * One labeled series (e.g. "Systolic", tinted to match its chart legend swatch) feeding
 * into [PeriodAverageSummaryGroup].
 */
data class LabeledPeriodAverage(
    val label: String,
    val color: Color,
    val summary: PeriodAverageSummary,
)
