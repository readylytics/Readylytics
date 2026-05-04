package com.gregor.lauritz.healthdashboard.domain.dashboard

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.efficiencyStatus
import com.gregor.lauritz.healthdashboard.domain.model.hrvStatus
import com.gregor.lauritz.healthdashboard.domain.model.paiStatus
import com.gregor.lauritz.healthdashboard.domain.model.restingHrStatus
import com.gregor.lauritz.healthdashboard.domain.model.rhrStatus
import com.gregor.lauritz.healthdashboard.domain.model.sleepDurationStatus
import com.gregor.lauritz.healthdashboard.domain.util.roundToPercentInt
import com.gregor.lauritz.healthdashboard.ui.dashboard.CardData
import com.gregor.lauritz.healthdashboard.ui.dashboard.DashboardAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetDashboardDataUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getWorkoutMetricsUseCase: GetWorkoutMetricsUseCase,
) {
    data class DashboardCards(
        val cardDataMap: Map<CardId, CardData>,
        val paiDailyBreakdown: List<Pair<String, Float>>,
    )

    operator fun invoke(
        summary: DailySummary?,
        prefs: UserPreferences,
        date: LocalDate,
        lastSleepSession: SleepSessionEntity?,
        paiSummaries: List<DailySummary>,
    ): DashboardCards {
        val cardDataMap = calculateCardData(summary, prefs, date, lastSleepSession)
        val paiDailyBreakdown = buildPaiBreakdown(date, paiSummaries)

        return DashboardCards(
            cardDataMap = cardDataMap,
            paiDailyBreakdown = paiDailyBreakdown,
        )
    }

    private fun calculateCardData(
        summary: DailySummary?,
        prefs: UserPreferences,
        selectedDate: LocalDate,
        lastSleepSession: SleepSessionEntity?,
    ): Map<CardId, CardData> {
        if (summary == null) return emptyMap()

        val mapBuilder = mutableMapOf<CardId, CardData>(
            CardId.SLEEP_RHR to sleepCard(summary, prefs),
            CardId.HRV to hrvCard(summary, prefs),
            CardId.PAI_DAILY to paiCard(summary),
            CardId.SLEEP_DURATION to sleepDurationCard(summary, prefs, lastSleepSession),
            CardId.RESTING_HR to restingHrCard(summary, prefs),
            CardId.SLEEP_EFFICIENCY to sleepEfficiencyCard(lastSleepSession),
        )

        val metrics = getWorkoutMetricsUseCase(summary)
        if (metrics.strainRatioCard != null) {
            mapBuilder[CardId.STRAIN_RATIO] = metrics.strainRatioCard
        }

        return mapBuilder.toMap()
    }

    private fun sleepEfficiencyCard(lastSleepSession: SleepSessionEntity?): CardData {
        val efficiencyStatus = lastSleepSession?.efficiencyStatus() ?: MetricStatus.CALIBRATING
        val efficiency = lastSleepSession?.efficiency?.roundToPercentInt()?.toString() ?: "—"

        return CardData(
            title = "Sleep Efficiency",
            value = if (efficiency == "—") efficiency else "$efficiency%",
            unit = "",
            status = efficiencyStatus,
            action = DashboardAction.NAVIGATE_SLEEP,
            tooltip = "The percentage of time actually asleep while in bed. (Goal: >85%).",
            secondaryText = "Goal: >85%"
        )
    }

    private fun paiCard(summary: DailySummary): CardData {
        val status = summary.paiStatus()
        val value = summary.totalPai?.roundToPercentInt()?.toString() ?: "—"

        return CardData(
            title = "PAI Score",
            value = value,
            unit = "",
            status = status,
            action = DashboardAction.NAVIGATE_WORKOUTS,
            tooltip = context.getString(R.string.tooltip_pai)
        )
    }

    private fun buildPaiBreakdown(
        endDate: LocalDate,
        summaries: List<DailySummary>,
    ): List<Pair<String, Float>> {
        val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        return (6 downTo 0).map { daysBack ->
            val day = endDate.minusDays(daysBack.toLong())
            val entry = summaries.firstOrNull { it.date == day }
            day.format(fmt) to (entry?.paiScore ?: 0f)
        }
    }

    private fun sleepCard(
        summary: DailySummary,
        prefs: UserPreferences
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

        val tooltip = buildString {
            append(context.getString(R.string.tooltip_sleep_rhr))
            if (rhrBaseline != null && rhrArrow != null && rhrDiff != null) {
                append(context.getString(R.string.tooltip_sleep_rhr_baseline, rhrBaseline, rhrArrow, rhrDiff))
            } else {
                append(context.getString(R.string.tooltip_sleep_rhr_no_baseline))
            }
        }

        return CardData(
            title = "Sleep RHR",
            value = summary.nocturnalRhr?.toString() ?: "—",
            unit = "bpm",
            status = rhrStatus,
            action = DashboardAction.NAVIGATE_SLEEP,
            tooltip = tooltip,
        )
    }

    private fun hrvCard(
        summary: DailySummary,
        prefs: UserPreferences
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

        val tooltip = buildString {
            append(context.getString(R.string.tooltip_sleep_hrv))
            if (hrvBaseline != null) {
                if (hrvArrow != null && hrvDiff != null) {
                    append(context.getString(R.string.tooltip_sleep_hrv_baseline, hrvBaseline, hrvArrow, hrvDiff))
                } else {
                    append(context.getString(R.string.tooltip_sleep_hrv_baseline_no_today, hrvBaseline))
                }
            } else {
                append(context.getString(R.string.tooltip_sleep_hrv_no_baseline))
            }
            val z = summary.zLnHrv
            val sigma = summary.hrvSigma
            if (z != null && sigma != null) {
                val zStr = String.format(Locale.getDefault(), "%.2f", z)
                val sigmaStr = String.format(Locale.getDefault(), "%.3f", sigma)
                append(context.getString(R.string.tooltip_sleep_hrv_diagnostics, zStr, sigmaStr))
            }
        }

        return CardData(
            title = "Sleep HRV",
            value = summary.nocturnalHrv?.toString() ?: "—",
            unit = "ms",
            status = hrvStatus,
            action = DashboardAction.NAVIGATE_SLEEP,
            tooltip = tooltip,
        )
    }

    private fun sleepDurationCard(
        summary: DailySummary,
        prefs: UserPreferences,
        lastSleepSession: SleepSessionEntity?,
    ): CardData {
        val durationStatus = summary.sleepDurationStatus((prefs.goalSleepHours * 60).toInt())
        val lastNightText = lastSleepSession?.let { session ->
            val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
            val zone = ZoneId.systemDefault()
            val bed = Instant.ofEpochMilli(session.startTime).atZone(zone).format(fmt)
            val wake = Instant.ofEpochMilli(session.endTime).atZone(zone).format(fmt)
            "$bed→$wake"
        }
        
        val goalStr = formatSleepDuration((prefs.goalSleepHours * 60).toInt())
        
        return CardData(
            title = "Sleep Duration",
            value = formatSleepDuration(summary.sleepDurationMinutes),
            unit = "",
            status = durationStatus,
            action = DashboardAction.NAVIGATE_SLEEP,
            tooltip = context.getString(R.string.tooltip_sleep_duration, goalStr),
        ).let {
            if (lastNightText != null) it.copy(secondaryText = lastNightText) else it
        }
    }

    private fun restingHrCard(
        summary: DailySummary,
        prefs: UserPreferences
    ): CardData {
        val restingHrStatus = summary.restingHrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)
        
        val tooltip = buildString {
            val rBaseline = summary.restingHrBaseline
            val rCurrent = summary.restingHeartRate
            if (rBaseline != null && rCurrent != null) {
                val diff = kotlin.math.abs(rCurrent - rBaseline)
                val arrow = when {
                    rCurrent > rBaseline -> "↑"
                    rCurrent < rBaseline -> "↓"
                    else -> "="
                }
                append(context.getString(R.string.tooltip_resting_hr_baseline, prefs.restingHrBeforeMinutes, prefs.restingHrAfterMinutes, rBaseline, arrow, diff))
            } else {
                append(context.getString(R.string.tooltip_resting_hr_no_baseline))
            }
        }
        
        return CardData(
            title = "Resting HR",
            value = summary.restingHeartRate?.toString() ?: "—",
            unit = "bpm",
            status = restingHrStatus,
            action = DashboardAction.NAVIGATE_RHR,
            tooltip = tooltip,
        )
    }

    fun formatSleepDuration(minutes: Int?): String {
        if (minutes == null) return "—"
        val hours = minutes / 60
        val mins = minutes % 60
        return if (mins == 0) "${hours}h" else "${hours}h ${mins}m"
    }
}
