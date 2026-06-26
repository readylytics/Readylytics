package app.readylytics.health.data.repository

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
        @param:ApplicationScope private val appScope: CoroutineScope,
    ) {
        private val dateMutex = Mutex()
        private val _selectedDate = MutableStateFlow(LocalDate.now())
        val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

        // Tracks what "today" was as of the last foreground check, so
        // advanceTodayIfNeeded() can tell "passively viewing today" (selectedDate
        // == lastKnownToday) apart from an explicit historical pick.
        private var lastKnownToday: LocalDate = _selectedDate.value

        val earliestDate: StateFlow<LocalDate?> =
            combine(
                dao.observeEarliestDateMs(),
                sleepSessionDao?.observeEarliestSessionTime() ?: flowOf(null),
                heartRateDao?.observeEarliestHrTime() ?: flowOf(null),
                hrvDao?.observeEarliestHrvTime() ?: flowOf(null),
                oxygenSaturationRecordDao?.observeEarliestSpo2Time() ?: flowOf(null),
                bloodPressureRecordDao?.observeEarliestBpTime() ?: flowOf(null),
            ) { times ->
                val minTime = times.filterNotNull().minOrNull()
                minTime?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
            }.stateIn(scope = appScope, started = SharingStarted.Eagerly, initialValue = null)

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

        suspend fun updateSelectedDate(date: LocalDate) {
            dateMutex.withLock {
                val today = LocalDate.now()
                val earliest = earliestDate.value
                _selectedDate.value =
                    date.coerceAtMost(today).let { d ->
                        if (earliest != null) d.coerceAtLeast(earliest) else d
                    }
                lastKnownToday = today
            }
        }

        suspend fun resetToToday() {
            dateMutex.withLock {
                val today = LocalDate.now()
                _selectedDate.value = today
                lastKnownToday = today
            }
        }

        // Called on app foreground. Advances to the new "today" only if the user
        // was passively on the previously-known today and the calendar day has
        // actually moved forward since the last check; an explicit historical
        // selection is left untouched.
        suspend fun advanceTodayIfNeeded() {
            dateMutex.withLock {
                val today = LocalDate.now()
                val previousToday = lastKnownToday
                if (_selectedDate.value == previousToday && today != previousToday) {
                    _selectedDate.value = today
                } else if (_selectedDate.value.isAfter(today)) {
                    _selectedDate.value = today
                }
                lastKnownToday = today
            }
        }

        suspend fun selectPreviousDay() {
            dateMutex.withLock {
                val candidate = _selectedDate.value.minusDays(1)
                val earliest = earliestDate.value
                if (earliest == null || candidate >= earliest) {
                    _selectedDate.value = candidate
                }
                lastKnownToday = LocalDate.now()
            }
        }

        suspend fun selectNextDay() {
            dateMutex.withLock {
                val today = LocalDate.now()
                if (_selectedDate.value < today) {
                    _selectedDate.value = _selectedDate.value.plusDays(1)
                }
                lastKnownToday = today
            }
        }
    }
