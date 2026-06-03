package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.OxygenSaturationRecordDao
import com.gregor.lauritz.healthdashboard.data.local.dao.BloodPressureRecordDao
import com.gregor.lauritz.healthdashboard.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
        private val sleepSessionDao: SleepSessionDao? = null,
        private val heartRateDao: HeartRateDao? = null,
        private val hrvDao: HrvDao? = null,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao? = null,
        private val bloodPressureRecordDao: BloodPressureRecordDao? = null,
        @ApplicationScope private val appScope: CoroutineScope,
    ) {
        private val dateMutex = Mutex()
        private val _selectedDate = MutableStateFlow(LocalDate.now())
        val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

        val earliestDate: StateFlow<LocalDate?> =
            combine(
                dao.observeEarliestDateMs(),
                sleepSessionDao?.observeEarliestSessionTime() ?: flowOf(null),
                heartRateDao?.observeEarliestHrTime() ?: flowOf(null),
                hrvDao?.observeEarliestHrvTime() ?: flowOf(null),
                oxygenSaturationRecordDao?.observeEarliestSpo2Time() ?: flowOf(null),
                bloodPressureRecordDao?.observeEarliestBpTime() ?: flowOf(null)
            ) { times ->
                val minTime = times.filterNotNull().minOrNull()
                minTime?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            }
                .stateIn(scope = appScope, started = SharingStarted.Eagerly, initialValue = null)

        init {
            appScope.launch {
                earliestDate.collect { earliest ->
                    if (earliest != null) {
                        dateMutex.withLock {
                            if (_selectedDate.value.isBefore(earliest)) {
                                _selectedDate.value = earliest
                            }
                        }
                    }
                }
            }
        }

        val availableDates: StateFlow<Set<LocalDate>> =
            dao.observeAllDateMidnightMs()
                .map { list ->
                    list.map { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }.toSet()
                }
                .stateIn(scope = appScope, started = SharingStarted.Eagerly, initialValue = emptySet())

        suspend fun updateSelectedDate(date: LocalDate) {
            dateMutex.withLock {
                val today = LocalDate.now()
                val earliest = earliestDate.value
                _selectedDate.value = date.coerceAtMost(today).let { d ->
                    if (earliest != null) d.coerceAtLeast(earliest) else d
                }
            }
        }

        suspend fun resetToToday() {
            dateMutex.withLock {
                _selectedDate.value = LocalDate.now()
            }
        }

        suspend fun selectPreviousDay() {
            dateMutex.withLock {
                val candidate = _selectedDate.value.minusDays(1)
                val earliest = earliestDate.value
                if (earliest == null || candidate >= earliest) {
                    _selectedDate.value = candidate
                }
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
