package com.gregor.lauritz.healthdashboard.ui.dashboard

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.data.repository.SelectedDateRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.CircadianConsistencyResult
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import com.gregor.lauritz.healthdashboard.domain.sync.ForegroundSyncController
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@Immutable
data class DashboardUiState(
    val summary: DailySummaryEntity? = null,
    val selectedDate: LocalDate = LocalDate.now(),
    val isRefreshing: Boolean = false,
    val cardData: List<CardData> = emptyList(),
    val cardRows: List<List<CardData>> = emptyList(),
    val circadianConsistency: CircadianConsistencyResult? = null,
)

@Immutable
data class CardData(
    val title: String,
    val value: String,
    val unit: String,
    val status: MetricStatus,
    val tooltip: String,
    val action: DashboardAction? = null,
)

enum class DashboardAction {
    NAVIGATE_SLEEP,
    NAVIGATE_WORKOUTS,
    NAVIGATE_RHR,
}

fun DailySummaryEntity.rhrStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val ratio = rhrRatio ?: return MetricStatus.CALIBRATING
    val poorThreshold = warningThreshold + (warningThreshold - 1)
    return when {
        ratio <= optimalThreshold -> MetricStatus.OPTIMAL
        ratio < warningThreshold -> MetricStatus.NEUTRAL
        ratio in warningThreshold..<poorThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummaryEntity.restingHrStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val rhr = restingHeartRate ?: return MetricStatus.CALIBRATING
    // Calculate ratio on the fly if stored ratio is missing but we have a baseline
    val ratio =
        restingHrRatio ?: restingHrBaseline?.let { baseline ->
            if (baseline > 0) rhr.toFloat() / baseline else null
        } ?: rhrRatio // Fallback to nocturnal ratio if specific resting ratio/baseline is missing

    if (ratio == null) return MetricStatus.CALIBRATING

    val poorThreshold = warningThreshold + (warningThreshold - 1)
    return when {
        ratio <= optimalThreshold -> MetricStatus.OPTIMAL
        ratio < warningThreshold -> MetricStatus.NEUTRAL
        ratio in warningThreshold..<poorThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummaryEntity.hrvStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val hrv = nocturnalHrv ?: return MetricStatus.CALIBRATING
    val baseline = hrvBaseline ?: return MetricStatus.CALIBRATING
    val ratio = hrv.toFloat() / baseline
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
        ratio >= ScoringConstants.Sleep.DURATION_OPTIMAL_RATIO -> MetricStatus.OPTIMAL
        ratio >= ScoringConstants.Sleep.DURATION_NEUTRAL_RATIO -> MetricStatus.NEUTRAL
        ratio >= ScoringConstants.Sleep.DURATION_WARNING_RATIO -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dailySummaryDao: DailySummaryDao,
        private val foregroundSyncController: ForegroundSyncController,
        private val selectedDateRepository: SelectedDateRepository,
        prefsRepo: UserPreferencesRepository,
        circadianRepo: CircadianConsistencyRepository,
    ) : ViewModel() {
        private val _isRefreshing = MutableStateFlow(false)

        val today: StateFlow<LocalDate> = selectedDateRepository.selectedDate
            .map { it ?: LocalDate.now() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LocalDate.now(),
            )

        @OptIn(ExperimentalCoroutinesApi::class)
        val uiState =
            selectedDateRepository.selectedDate
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
                        circadianRepo.result,
                    ) { summary, prefs, refreshing, circadian ->
                        val data =
                            calculateCardData(
                                summary,
                                prefs,
                                date,
                            )
                        DashboardUiState(
                            summary = summary,
                            selectedDate = date,
                            isRefreshing = refreshing,
                            cardData = data,
                            cardRows = data.chunked(2),
                            circadianConsistency = circadian,
                        )
                    }.flowOn(Dispatchers.Default)
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5_000),
                    initialValue = DashboardUiState(),
                )

        private fun calculateCardData(
            summary: DailySummaryEntity?,
            prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences,
            selectedDate: LocalDate,
        ): List<CardData> {
            if (summary == null) return emptyList()

            return listOf(
                sleepCard(summary, prefs),
                hrvCard(summary, prefs),
                sleepDurationCard(summary, prefs),
                restingHrCard(summary, prefs)
            )
        }

        private fun sleepCard(
            summary: DailySummaryEntity,
            prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
        ): CardData {
            val rhrStatus = summary.rhrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)
            val rhrBaseline = summary.let { s ->
                val ratio = s.rhrRatio
                val rhr = s.nocturnalRhr
                if (ratio != null && ratio > 0f && rhr != null) (rhr / ratio).toInt() else null
            }
            val rhrDiff = summary.let { s ->
                val ratio = s.rhrRatio
                val rhr = s.nocturnalRhr
                if (ratio != null && ratio > 0f && rhr != null) {
                    val baseline = (rhr / ratio).toInt()
                    kotlin.math.abs(rhr - baseline)
                } else null
            }
            val rhrArrow = if (rhrBaseline != null && summary.nocturnalRhr != null) {
                when {
                    summary.nocturnalRhr > rhrBaseline -> "↑"
                    summary.nocturnalRhr < rhrBaseline -> "↓"
                    else -> "="
                }
            } else null

            return CardData(
                title = "Sleep RHR",
                value = summary.nocturnalRhr?.toString() ?: "—",
                unit = "bpm",
                status = rhrStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = buildString {
                    append("Average heart rate during sleep.\n")
                    append("Target: lower or equal to your 30-day rolling average. ")
                    append("(Lower = Recovered)")
                    if (rhrBaseline != null && rhrArrow != null && rhrDiff != null) {
                        append("\n\nBaseline: $rhrBaseline bpm $rhrArrow ($rhrDiff bpm)")
                    } else {
                        append("\n\nNot enough data to calculate baseline.")
                    }
                },
            )
        }

        private fun hrvCard(
            summary: DailySummaryEntity,
            prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
        ): CardData {
            val hrvStatus = summary.hrvStatus(prefs.hrvOptimalThreshold, prefs.hrvWarningThreshold)
            val hrvBaseline = summary.hrvBaseline
            val hrvDiff = summary.let { s ->
                val baseline = s.hrvBaseline
                val hrv = s.nocturnalHrv
                if (baseline != null && hrv != null) kotlin.math.abs(hrv - baseline) else null
            }
            val hrvArrow = if (hrvBaseline != null && summary.nocturnalHrv != null) {
                when {
                    summary.nocturnalHrv > hrvBaseline -> "↑"
                    summary.nocturnalHrv < hrvBaseline -> "↓"
                    else -> "="
                }
            } else null

            return CardData(
                title = "Sleep HRV",
                value = summary.nocturnalHrv?.toString() ?: "—",
                unit = "ms",
                status = hrvStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = buildString {
                    append("Variation between heartbeats in milliseconds.")
                    append("\nTarget: Within or above your 30-day rolling average. ")
                    append("(Higher = Recovered)")
                    if (hrvBaseline != null && hrvArrow != null && hrvDiff != null) {
                        append("\n\nBaseline: $hrvBaseline ms $hrvArrow ($hrvDiff ms)")
                    } else {
                        append("\n\nNot enough data to calculate baseline.")
                    }
                },
            )
        }

        private fun sleepDurationCard(
            summary: DailySummaryEntity,
            prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
        ): CardData {
            val durationStatus = summary.sleepDurationStatus((prefs.goalSleepHours * 60).toInt())
            return CardData(
                title = "Sleep Duration",
                value = formatSleepDuration(summary.sleepDurationMinutes),
                unit = "",
                status = durationStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = buildString {
                    append("Total time asleep last night.")
                    val goal = formatSleepDuration((prefs.goalSleepHours * 60).toInt())
                    append("\n\nGoal: $goal")
                },
            )
        }

        private fun restingHrCard(
            summary: DailySummaryEntity,
            prefs: com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
        ): CardData {
            val restingHrStatus = summary.restingHrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)
            return CardData(
                title = "Resting HR",
                value = summary.restingHeartRate?.toString() ?: "—",
                unit = "bpm",
                status = restingHrStatus,
                action = DashboardAction.NAVIGATE_RHR,
                tooltip = buildString {
                    val rBaseline = summary.restingHrBaseline
                    val rCurrent = summary.restingHeartRate
                    if (rBaseline != null && rCurrent != null) {
                        val diff = kotlin.math.abs(rCurrent - rBaseline)
                        val arrow = when {
                            rCurrent > rBaseline -> "↑"
                            rCurrent < rBaseline -> "↓"
                            else -> "="
                        }
                        append("Minimum heart rate captured within ")
                        append("${prefs.restingHrBeforeMinutes}m before and ")
                        append("${prefs.restingHrAfterMinutes}m after wake up.")
                        append("\n\nBaseline: $rBaseline bpm $arrow ($diff bpm)")
                    } else {
                        append("Minimum heart rate captured around wake up time.")
                        append("\n\nNot enough data to calculate baseline.")
                    }
                },
            )
        }

        fun formatSleepDuration(minutes: Int?): String {
            if (minutes == null) return "—"
            val hours = minutes / 60
            val mins = minutes % 60
            return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
        }

        fun onRefresh() = viewModelScope.launch {
            _isRefreshing.value = true
            try {
                foregroundSyncController.triggerImmediateSync()
            } catch (_: Exception) {
                // Sync errors are non-fatal for the UI — the state will update once data arrives
            } finally {
                _isRefreshing.value = false
            }
        }

        fun onPreviousDay() {
            selectedDateRepository.selectPreviousDay()
        }

        fun onNextDay() {
            selectedDateRepository.selectNextDay()
        }
    }
