package app.readylytics.health.domain.date

import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

interface SelectedDateStore {
    val selectedDate: StateFlow<LocalDate>
    val earliestDate: StateFlow<LocalDate?>

    suspend fun updateSelectedDate(date: LocalDate)

    suspend fun resetToToday()

    suspend fun advanceTodayIfNeeded()

    suspend fun selectPreviousDay()

    suspend fun selectNextDay()
}
