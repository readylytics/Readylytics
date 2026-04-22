package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.ui.components.MetricStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val summary: DailySummaryEntity? = null,
    val goalSleepMinutes: Int = 480,
    val hrvOptimalThreshold: Float = 0.95f,
    val hrvWarningThreshold: Float = 0.85f,
    val rhrOptimalThreshold: Float = 1.05f,
    val rhrWarningThreshold: Float = 1.15f,
    val isRefreshing: Boolean = false,
)

fun DailySummaryEntity.rhrStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus =
    when {
        rhrRatio == null -> MetricStatus.CALIBRATING
        rhrRatio <= optimalThreshold -> MetricStatus.OPTIMAL
        rhrRatio <= warningThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun DailySummaryEntity.hrvStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val hrv = nocturnalHrv ?: return MetricStatus.CALIBRATING
    val baseline = hrvBaseline ?: return MetricStatus.CALIBRATING
    val ratio = hrv / baseline
    return when {
        ratio >= optimalThreshold -> MetricStatus.OPTIMAL
        ratio >= warningThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummaryEntity.sleepDurationStatus(goalMinutes: Int): MetricStatus {
    if (sleepDurationMinutes == null || goalMinutes <= 0) return MetricStatus.CALIBRATING
    val ratio = sleepDurationMinutes.toFloat() / goalMinutes
    return when {
        ratio >= 0.9f -> MetricStatus.OPTIMAL
        ratio >= 0.75f -> MetricStatus.WARNING
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
        private val isRefreshing = MutableStateFlow(false)

        val uiState =
            combine(
                dailySummaryDao.observeLatest(),
                prefsRepo.userPreferences,
                isRefreshing,
            ) { summary, prefs, refreshing ->
                DashboardUiState(
                    summary = summary,
                    goalSleepMinutes = (prefs.goalSleepHours * 60).toInt(),
                    hrvOptimalThreshold = prefs.hrvOptimalThreshold,
                    hrvWarningThreshold = prefs.hrvWarningThreshold,
                    rhrOptimalThreshold = prefs.rhrOptimalThreshold,
                    rhrWarningThreshold = prefs.rhrWarningThreshold,
                    isRefreshing = refreshing,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DashboardUiState(),
            )

        fun onRefresh() {
            viewModelScope.launch {
                isRefreshing.value = true
                foregroundSyncController.triggerImmediateSync()
                isRefreshing.value = false
            }
        }
    }
