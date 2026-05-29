package com.gregor.lauritz.healthdashboard.ui.dashboard

import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.hrvStatus
import com.gregor.lauritz.healthdashboard.domain.model.paiStatus
import com.gregor.lauritz.healthdashboard.domain.model.restingHrStatus
import com.gregor.lauritz.healthdashboard.domain.model.rhrStatus
import com.gregor.lauritz.healthdashboard.domain.model.sleepDurationStatus
import com.gregor.lauritz.healthdashboard.domain.repository.SleepSessionData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashboardUiMapper
    @Inject
    constructor() {
        fun calculateCardData(
            summary: DailySummary?,
            prefs: UserPreferences,
            selectedDate: LocalDate,
            lastSleepSession: SleepSessionData?,
        ): List<CardData> {
            if (summary == null) return emptyList()

            return listOf(
                sleepCard(summary, prefs),
                hrvCard(summary, prefs),
                paiCard(summary),
                sleepDurationCard(summary, prefs, lastSleepSession),
            )
        }

        fun paiCard(summary: DailySummary): CardData {
            val status = summary.paiStatus()
            val value = summary.totalPai?.toInt()?.toString() ?: "—"

            return CardData(
                title = "Current PAI",
                value = value,
                unit = "",
                status = status,
                action = DashboardAction.NAVIGATE_WORKOUTS,
                tooltip =
                    buildString {
                        append("Your 7-day rolling heart health score.\n")
                        append("Based on how often and how hard you challenge your heart.\n\n")
                        append("• 100+: Optimal\n")
                        append("• 75-99: Neutral\n")
                        append("• 50-74: Warning\n")
                        append("• < 50: Poor")
                    },
            )
        }

        fun sleepCard(
            summary: DailySummary,
            prefs: UserPreferences,
        ): CardData {
            val rhrStatus = summary.rhrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)
            val rhrBaseline =
                summary.let { s ->
                    val ratio = s.rhrRatio
                    val rhr = s.nocturnalRhr
                    if (ratio != null && ratio > 0f && rhr != null) (rhr / ratio).toInt() else null
                }
            val rhrDiff =
                summary.let { s ->
                    val ratio = s.rhrRatio
                    val rhr = s.nocturnalRhr
                    if (ratio != null && ratio > 0f && rhr != null) {
                        val baseline = (rhr / ratio).toInt()
                        kotlin.math.abs(rhr - baseline)
                    } else {
                        null
                    }
                }
            val rhrArrow =
                if (rhrBaseline != null && summary.nocturnalRhr != null) {
                    when {
                        summary.nocturnalRhr > rhrBaseline -> "↑"
                        summary.nocturnalRhr < rhrBaseline -> "↓"
                        else -> "="
                    }
                } else {
                    null
                }

            return CardData(
                title = "Sleep RHR",
                value = summary.nocturnalRhr?.toString() ?: "—",
                unit = "bpm",
                status = rhrStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip =
                    buildString {
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

        fun hrvCard(
            summary: DailySummary,
            prefs: UserPreferences,
        ): CardData {
            val hrvStatus = summary.hrvStatus(prefs.hrvOptimalThreshold, prefs.hrvWarningThreshold)
            val hrvBaseline = summary.hrvBaseline
            val hrvDiff =
                summary.let { s ->
                    val baseline = s.hrvBaseline
                    val hrv = s.nocturnalHrv
                    if (baseline != null && hrv != null) kotlin.math.abs(hrv - baseline) else null
                }
            val hrvArrow =
                if (hrvBaseline != null && summary.nocturnalHrv != null) {
                    when {
                        summary.nocturnalHrv > hrvBaseline -> "↑"
                        summary.nocturnalHrv < hrvBaseline -> "↓"
                        else -> "="
                    }
                } else {
                    null
                }

            return CardData(
                title = "Sleep HRV",
                value = summary.nocturnalHrv?.toString() ?: "—",
                unit = "ms",
                status = hrvStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip =
                    buildString {
                        append("Variation between heartbeats in milliseconds.")
                        append("\nTarget: Within or above your 30-day rolling average. ")
                        append("(Higher = Recovered)")
                        if (hrvBaseline != null) {
                            if (hrvArrow != null && hrvDiff != null) {
                                append("\n\nBaseline: $hrvBaseline ms $hrvArrow ($hrvDiff ms)")
                            } else {
                                append("\n\nBaseline: $hrvBaseline ms (today's HRV not yet available)")
                            }
                        } else {
                            append("\n\nNot enough data to calculate baseline.")
                        }
                        val z = summary.zLnHrv
                        val sigma = summary.hrvSigma
                        if (z != null && sigma != null) {
                            append("\n\nDiagnostics:")
                            append("\n• Z-score: ${String.format(Locale.getDefault(), "%.2f", z)}")
                            append("\n• σ (ln-scale): ${String.format(Locale.getDefault(), "%.3f", sigma)}")
                        }
                    },
            )
        }

        fun sleepDurationCard(
            summary: DailySummary,
            prefs: UserPreferences,
            lastSleepSession: SleepSessionData?,
        ): CardData {
            val durationStatus = summary.sleepDurationStatus((prefs.goalSleepHours * 60).toInt())
            val lastNightText =
                lastSleepSession?.let { session ->
                    val fmt = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                    val zone = ZoneId.systemDefault()
                    val bed = Instant.ofEpochMilli(session.startTime).atZone(zone).format(fmt)
                    val wake = Instant.ofEpochMilli(session.endTime).atZone(zone).format(fmt)
                    "$bed→$wake"
                }
            return CardData(
                title = "Sleep Duration",
                value = formatSleepDuration(summary.sleepDurationMinutes),
                unit = "",
                status = durationStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip =
                    buildString {
                        append("Total time asleep last night.")
                        val goal = formatSleepDuration((prefs.goalSleepHours * 60).toInt())
                        append("\n\nGoal: $goal")
                    },
            ).let {
                if (lastNightText != null) it.copy(secondaryText = lastNightText) else it
            }
        }

        fun restingHrCard(
            summary: DailySummary,
            prefs: UserPreferences,
        ): CardData {
            val restingHrStatus = summary.restingHrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)
            return CardData(
                title = "Resting Heart Rate",
                value = summary.restingHeartRate?.toString() ?: "—",
                unit = "bpm",
                status = restingHrStatus,
                action = DashboardAction.NAVIGATE_RHR,
                tooltip =
                    buildString {
                        val rBaseline = summary.restingHrBaseline
                        val rCurrent = summary.restingHeartRate
                        if (rBaseline != null && rCurrent != null) {
                            val diff = kotlin.math.abs(rCurrent - rBaseline)
                            val arrow =
                                when {
                                    rCurrent > rBaseline -> "↑"
                                    rCurrent < rBaseline -> "↓"
                                    else -> "="
                                }
                            append("Resting heart rate calculated as the low-percentile of the entire sleep period.")
                            append("\n\nBaseline: $rBaseline bpm $arrow ($diff bpm)")
                        } else {
                            append("Resting heart rate calculated as the low-percentile of the entire sleep period.")
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

        fun buildPaiBreakdown(
            endDate: LocalDate,
            summaries: List<DailySummary>,
        ): List<Pair<String, Float>> {
            val zoneId = ZoneId.systemDefault()
            val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            return (6 downTo 0).map { daysBack ->
                val day = endDate.minusDays(daysBack.toLong())
                val entry = summaries.firstOrNull { it.date == day }
                day.format(fmt) to (entry?.paiScore ?: 0f)
            }
        }
    }
