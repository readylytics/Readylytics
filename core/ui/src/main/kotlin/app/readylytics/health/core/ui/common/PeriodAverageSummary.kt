package app.readylytics.health.core.ui.common

import java.time.LocalDate

/**
 * Latest-bucket-vs-prior-bucket summary for a bucketed trend series.
 * [average] is the latest populated bucket's average, [previousAverage] the bucket before it.
 * Labels are intentionally not preformatted: [periodStartDate]/[previousPeriodStartDate] carry the
 * bucket midpoint dates so the UI layer can format them (quarter labels come from `strings.xml`).
 */
data class PeriodAverageSummary(
    val granularity: TrendGranularity,
    val periodStartDate: LocalDate,
    val previousPeriodStartDate: LocalDate,
    val average: Float?,
    val previousAverage: Float?,
)
