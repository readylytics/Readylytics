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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val summary: DailySummaryEntity? = null,
    val goalSleepMinutes: Int = 480,
    val isRefreshing: Boolean = false,
)

fun DailySummaryEntity.rhrStatus(): MetricStatus =
    when {
        rhrRatio == null -> MetricStatus.CALIBRATING
        rhrRatio <= 1.05f -> MetricStatus.OPTIMAL
        rhrRatio <= 1.15f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun DailySummaryEntity.hrvStatus(): MetricStatus {
    val hrv = nocturnalHrv ?: return MetricStatus.CALIBRATING
    val baseline = hrvBaseline ?: return MetricStatus.CALIBRATING
    val ratio = hrv / baseline
    return when {
        ratio >= 0.95f -> MetricStatus.OPTIMAL
        ratio >= 0.85f -> MetricStatus.WARNING
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
                prefsRepo.userPreferences.map { (it.goalSleepHours * 60).toInt() },
                isRefreshing,
            ) { summary, goalMinutes, refreshing ->
                DashboardUiState(
                    summary = summary,
                    goalSleepMinutes = goalMinutes,
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
