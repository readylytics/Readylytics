package com.gregor.lauritz.healthdashboard.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SelectedDateRepository @Inject constructor() {
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    fun updateSelectedDate(date: LocalDate) {
        val today = LocalDate.now()
        _selectedDate.value = if (date > today) today else date
    }

    fun selectPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun selectNextDay() {
        val today = LocalDate.now()
        if (_selectedDate.value < today) {
            _selectedDate.value = _selectedDate.value.plusDays(1)
        }
    }
}
