package app.readylytics.health.core.model.domain.repository

import java.time.LocalDate

/**
 * Derived daily RAS values held by [WalkForwardRasWindow].
 */
data class DailyRasValues(
    val date: LocalDate,
    val rasWorkoutOnly: Float?,
    val rasEverydayHr: Float?,
)

/**
 * PERF-002/WP-22: rolling 6-day window of committed daily RAS values shared across one
 * walk-forward recompute, eliminating redundant 6-row queries per recomputed day.
 *
 * Seeded with the 6 daily summaries prior to the walk-forward start date. Advances only
 * after a day's summary is successfully persisted.
 */
class WalkForwardRasWindow(
    seedDays: List<DailyRasValues> = emptyList(),
) {
    private val window = ArrayDeque<DailyRasValues>()

    init {
        seedDays.takeLast(MAX_WINDOW_DAYS).forEach { window.addLast(it) }
    }

    fun sumWorkoutOnlyBefore(targetDate: LocalDate): Float {
        val validDates = (1..MAX_WINDOW_DAYS).map { targetDate.minusDays(it.toLong()) }.toSet()
        return window.filter { it.date in validDates }.mapNotNull { it.rasWorkoutOnly }.sum()
    }

    fun sumEverydayHrBefore(targetDate: LocalDate): Float {
        val validDates = (1..MAX_WINDOW_DAYS).map { targetDate.minusDays(it.toLong()) }.toSet()
        return window.filter { it.date in validDates }.mapNotNull { it.rasEverydayHr }.sum()
    }

    fun commit(values: DailyRasValues) {
        window.addLast(values)
        if (window.size > MAX_WINDOW_DAYS) {
            window.removeFirst()
        }
    }

    fun currentEntries(): List<DailyRasValues> = window.toList()

    companion object {
        const val MAX_WINDOW_DAYS = 6
    }
}
