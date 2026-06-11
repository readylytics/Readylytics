package app.readylytics.health.domain.date

import java.time.LocalDate

/**
 * Safe date state transitions to prevent invalid date combinations.
 * All date mutations should go through this sealed hierarchy.
 */
sealed interface DateTransition {
    /**
     * No date change requested.
     */
    data object NoChange : DateTransition

    /**
     * Update to a specific date (capped at today, never in future).
     */
    data class UpdateTo(
        val date: LocalDate,
    ) : DateTransition

    /**
     * Move to the previous day.
     */
    data object PreviousDay : DateTransition

    /**
     * Move to the next day (blocked if already at today).
     */
    data object NextDay : DateTransition

    /**
     * Reset to today's date (idempotent).
     */
    data object ResetToToday : DateTransition
}

/**
 * Applies a DateTransition to the current date, returning the new date.
 * All validation happens here, not in the repository.
 *
 * @param currentDate The current selected date
 * @param transition The transition to apply
 * @return The new date after applying the transition
 */
fun applyDateTransition(
    currentDate: LocalDate,
    transition: DateTransition,
): LocalDate {
    val today = LocalDate.now()
    return when (transition) {
        is DateTransition.NoChange -> currentDate
        is DateTransition.UpdateTo -> {
            // Prevent future dates
            if (transition.date > today) today else transition.date
        }
        DateTransition.PreviousDay -> currentDate.minusDays(1)
        DateTransition.NextDay -> {
            // Only advance if not already at today
            if (currentDate < today) currentDate.plusDays(1) else today
        }
        DateTransition.ResetToToday -> today
    }
}

/**
 * Validates that a transition is safe from the current state.
 *
 * @return true if the transition is safe to execute
 */
fun DateTransition.isValidFrom(currentDate: LocalDate): Boolean {
    val today = LocalDate.now()
    return when (this) {
        is DateTransition.NoChange -> true
        is DateTransition.UpdateTo -> true // applyDateTransition handles capping
        DateTransition.PreviousDay -> true // Always valid to go backward
        DateTransition.NextDay -> currentDate < today // Only valid if not already at today
        DateTransition.ResetToToday -> true
    }
}
