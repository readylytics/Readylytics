package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SelectedDateRepository
    @Inject
    constructor(
        private val dao: DailySummaryDao,
        @ApplicationScope private val appScope: CoroutineScope,
    ) {
        private val _selectedDate = MutableStateFlow(LocalDate.now())
        val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

        val earliestDate: StateFlow<LocalDate?> =
            dao.observeEarliestDateMs()
                .map { ms -> ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } }
                .stateIn(scope = appScope, started = SharingStarted.Eagerly, initialValue = null)

        private val dateMutex = Mutex()

        suspend fun updateSelectedDate(date: LocalDate) {
            dateMutex.withLock {
                val today = LocalDate.now()
                _selectedDate.value = if (date > today) today else date
            }
        }

        suspend fun resetToToday() {
            dateMutex.withLock {
                _selectedDate.value = LocalDate.now()
            }
        }

        suspend fun selectPreviousDay() {
            dateMutex.withLock {
                _selectedDate.value = _selectedDate.value.minusDays(1)
            }
        }

        suspend fun selectNextDay() {
            dateMutex.withLock {
                val today = LocalDate.now()
                if (_selectedDate.value < today) {
                    _selectedDate.value = _selectedDate.value.plusDays(1)
                }
            }
        }
    }
