package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DashboardUiState(
    val summary: DailySummaryEntity? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val goalSleepMinutes: Int = 480,
    val hrvOptimalThreshold: Float = 0.95f,
    val hrvWarningThreshold: Float = 0.85f,
    val rhrOptimalThreshold: Float = 1.05f,
    val rhrWarningThreshold: Float = 1.15f,
    val restingHrBeforeMinutes: Int = 5,
    val restingHrAfterMinutes: Int = 15,
    val isRefreshing: Boolean = false,
)

fun DailySummaryEntity.rhrStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val poorThreshold = warningThreshold + (warningThreshold - 1)
    return when {
        rhrRatio == null -> MetricStatus.CALIBRATING
        rhrRatio <= optimalThreshold -> MetricStatus.OPTIMAL
        rhrRatio < warningThreshold -> MetricStatus.NEUTRAL
        rhrRatio in warningThreshold..<poorThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummaryEntity.restingHrStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val poorThreshold = warningThreshold + (warningThreshold - 1)
    return when {
        restingHrRatio == null -> MetricStatus.CALIBRATING
        restingHrRatio <= optimalThreshold -> MetricStatus.OPTIMAL
        restingHrRatio < warningThreshold -> MetricStatus.NEUTRAL
        restingHrRatio in warningThreshold..<poorThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummaryEntity.hrvStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val hrv = nocturnalHrv ?: return MetricStatus.CALIBRATING
    val baseline = hrvBaseline ?: return MetricStatus.CALIBRATING
    val ratio = hrv / baseline
    val poorThreshold = warningThreshold - (1 - warningThreshold)
    return when {
        ratio >= optimalThreshold -> MetricStatus.OPTIMAL
        ratio > warningThreshold -> MetricStatus.NEUTRAL
        ratio >= poorThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummaryEntity.sleepDurationStatus(goalMinutes: Int): MetricStatus {
    if (sleepDurationMinutes == null || goalMinutes <= 0) return MetricStatus.CALIBRATING
    val ratio = sleepDurationMinutes.toFloat() / goalMinutes
    return when {
        ratio >= 0.9f -> MetricStatus.OPTIMAL
        ratio >= 0.8f -> MetricStatus.NEUTRAL
        ratio >= 0.7f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val foregroundSyncController: ForegroundSyncController,
        prefsRepo: UserPreferencesRepository,
    ) : ViewModel() {
        private val _isRefreshing = MutableStateFlow(false)
        val isRefreshing = _isRefreshing

        private val _selectedDate = MutableStateFlow(LocalDate.now())
        val selectedDate = _selectedDate

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            _selectedDate
                .flatMapLatest { date ->
                    val today = LocalDate.now()
                    val summaryFlow =
                        if (date == today) {
                            dailySummaryDao.observeLatest()
                        } else {
                            val midnightMs =
                                date
                                    .atStartOfDay(ZoneId.systemDefault())
                                    .toInstant()
                                    .toEpochMilli()
                            flow { emit(dailySummaryDao.getByDate(midnightMs)) }
                        }
                    combine(
                        summaryFlow,
                        prefsRepo.userPreferences,
                        _isRefreshing,
                    ) { summary, prefs, refreshing ->
                        DashboardUiState(
                            summary = summary,
                            selectedDate = date,
                            goalSleepMinutes = (prefs.goalSleepHours * 60).toInt(),
                            hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                            hrvWarningThreshold = prefs.hrvWarningThreshold,
                            rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                            rhrWarningThreshold = prefs.rhrWarningThreshold,
                            restingHrBeforeMinutes = prefs.restingHrBeforeMinutes,
                            restingHrAfterMinutes = prefs.restingHrAfterMinutes,
                            isRefreshing = refreshing,
                        )
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = DashboardUiState(),
                )

        fun onRefresh() {
            viewModelScope.launch {
                _isRefreshing.value = true
                foregroundSyncController.triggerImmediateSync()
                _isRefreshing.value = false
            }
        }

        fun onPreviousDay() {
            _selectedDate.update { it.minusDays(1) }
        }

        fun onNextDay() {
            _selectedDate.update { d -> if (d < LocalDate.now()) d.plusDays(1) else d }
        }
    }
