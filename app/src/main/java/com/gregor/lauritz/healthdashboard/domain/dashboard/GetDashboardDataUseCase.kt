package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.R
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.calculation.HealthMetricsCalculator
import com.gregor.lauritz.healthdashboard.domain.model.BloodPressureStatus
import com.gregor.lauritz.healthdashboard.domain.model.BmiStatus
import com.gregor.lauritz.healthdashboard.domain.model.BodyFatStatus
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.SleepSessionSummary
import com.gregor.lauritz.healthdashboard.domain.model.efficiencyStatus
import com.gregor.lauritz.healthdashboard.domain.model.hrvStatus
import com.gregor.lauritz.healthdashboard.domain.model.paiStatus
import com.gregor.lauritz.healthdashboard.domain.model.restingHrStatus
import com.gregor.lauritz.healthdashboard.domain.model.rhrStatus
import com.gregor.lauritz.healthdashboard.domain.model.sleepDurationStatus
import com.gregor.lauritz.healthdashboard.domain.util.ResourceProvider
import com.gregor.lauritz.healthdashboard.domain.util.UnitConverter
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
class GetDashboardDataUseCase
    @Inject
    constructor(
        private val resourceProvider: ResourceProvider,
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
            lastSleepSession: SleepSessionSummary?,
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
            lastSleepSession: SleepSessionSummary?,
        ): Map<CardId, CardData> {
            if (summary == null) return emptyMap()

            val mapBuilder =
                mutableMapOf<CardId, CardData>(
                    CardId.SLEEP_RHR to sleepCard(summary, prefs),
                    CardId.HRV to hrvCard(summary, prefs),
                    CardId.PAI_DAILY to paiCard(summary),
                    CardId.SLEEP_DURATION to sleepDurationCard(summary, prefs, lastSleepSession),
                    CardId.RESTING_HR to restingHrCard(summary, prefs),
                    CardId.SLEEP_EFFICIENCY to sleepEfficiencyCard(lastSleepSession),
                )

            val metrics = getWorkoutMetricsUseCase(summary)
            metrics.strainRatioCard?.let { mapBuilder[CardId.STRAIN_RATIO] = it }

            // Add new health metrics
            mapBuilder[CardId.WEIGHT] = weightCard(summary, prefs)
            mapBuilder[CardId.BODY_FAT] = bodyFatCard(summary, prefs)
            mapBuilder[CardId.BLOOD_PRESSURE] = bloodPressureCard(summary)

            return mapBuilder.toMap()
        }

        private fun sleepEfficiencyCard(lastSleepSession: SleepSessionSummary?): CardData {
            val efficiencyStatus = lastSleepSession?.efficiencyStatus() ?: MetricStatus.CALIBRATING
            val efficiency = lastSleepSession?.efficiency?.roundToPercentInt()?.toString() ?: "—"

            return CardData(
                title = "Sleep Efficiency",
                value = if (efficiency == "—") efficiency else "$efficiency%",
                unit = "",
                status = efficiencyStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = "The percentage of time actually asleep while in bed. (Goal: >85%).",
                secondaryText = "Goal: >85%",
            )
        }

        private fun paiCard(summary: DailySummary): CardData {
            val status = summary.paiStatus()
            val value = summary.totalPai?.roundToPercentInt()?.toString() ?: "—"

            return CardData(
                title = "PAI",
                value = value,
                unit = "",
                status = status,
                action = DashboardAction.NAVIGATE_WORKOUTS,
                tooltip = resourceProvider.getString(R.string.tooltip_pai),
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

            val tooltip =
                buildString {
                    append(resourceProvider.getString(R.string.tooltip_sleep_rhr))
                    if (rhrBaseline != null && rhrArrow != null && rhrDiff != null) {
                        append(
                            resourceProvider.getString(
                                R.string.tooltip_sleep_rhr_baseline,
                                rhrBaseline,
                                rhrArrow,
                                rhrDiff,
                            ),
                        )
                    } else {
                        append(resourceProvider.getString(R.string.tooltip_sleep_rhr_no_baseline))
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

            val tooltip =
                buildString {
                    append(resourceProvider.getString(R.string.tooltip_sleep_hrv))
                    if (hrvBaseline != null) {
                        if (hrvArrow != null && hrvDiff != null) {
                            append(
                                resourceProvider.getString(
                                    R.string.tooltip_sleep_hrv_baseline,
                                    hrvBaseline,
                                    hrvArrow,
                                    hrvDiff,
                                ),
                            )
                        } else {
                            append(
                                resourceProvider.getString(R.string.tooltip_sleep_hrv_baseline_no_today, hrvBaseline),
                            )
                        }
                    } else {
                        append(resourceProvider.getString(R.string.tooltip_sleep_hrv_no_baseline))
                    }
                    val z = summary.zLnHrv
                    val sigma = summary.hrvSigma
                    if (z != null && sigma != null) {
                        val zStr = String.format(Locale.getDefault(), "%.2f", z)
                        val sigmaStr = String.format(Locale.getDefault(), "%.3f", sigma)
                        append(resourceProvider.getString(R.string.tooltip_sleep_hrv_diagnostics, zStr, sigmaStr))
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
            lastSleepSession: SleepSessionSummary?,
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

            val goalStr = formatSleepDuration((prefs.goalSleepHours * 60).toInt())

            return CardData(
                title = "Sleep Duration",
                value = formatSleepDuration(summary.sleepDurationMinutes),
                unit = "",
                status = durationStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = resourceProvider.getString(R.string.tooltip_sleep_duration, goalStr),
            ).let {
                if (lastNightText != null) it.copy(secondaryText = lastNightText) else it
            }
        }

        private fun restingHrCard(
            summary: DailySummary,
            prefs: UserPreferences,
        ): CardData {
            val restingHrStatus = summary.restingHrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)

            val tooltip =
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
                        append(
                            resourceProvider.getString(
                                R.string.tooltip_resting_hr_baseline,
                                prefs.restingHrBeforeMinutes,
                                prefs.restingHrAfterMinutes,
                                rBaseline,
                                arrow,
                                diff,
                            ),
                        )
                    } else {
                        append(resourceProvider.getString(R.string.tooltip_resting_hr_no_baseline))
                    }
                }

            return CardData(
                title = "Resting HR",
                value = (summary.restingHeartRate ?: summary.nocturnalRhr)?.toString() ?: "—",
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

        private fun weightCard(
            summary: DailySummary,
            prefs: UserPreferences,
        ): CardData {
            val unitStr = if (prefs.unitSystem == UnitSystem.METRIC) "kg" else "lbs"
            val weightKg =
                summary.weightKg ?: return CardData(
                    title = "Weight",
                    value = "—",
                    unit = unitStr,
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Weight from Health Connect",
                )

            val heightCm = prefs.heightCm
            val bmiStatus =
                if (heightCm != null) {
                    val bmi = HealthMetricsCalculator.calculateBmi(weightKg, heightCm)
                    val bmiStatusObj = HealthMetricsCalculator.assessBmi(bmi)
                    when (bmiStatusObj) {
                        BmiStatus.Optimal -> MetricStatus.OPTIMAL
                        BmiStatus.Neutral -> MetricStatus.NEUTRAL
                        BmiStatus.Warning -> MetricStatus.WARNING
                        BmiStatus.Poor -> MetricStatus.POOR
                    }
                } else {
                    MetricStatus.NEUTRAL
                }

            val displayWeight =
                if (prefs.unitSystem ==
                    UnitSystem.METRIC
                ) {
                    weightKg
                } else {
                    weightKg * UnitConverter.KG_TO_LBS
                }

            return CardData(
                title = "Weight",
                value = String.format(Locale.getDefault(), "%.1f", displayWeight),
                unit = unitStr,
                status = bmiStatus,
                action = DashboardAction.NAVIGATE_WEIGHT,
                tooltip = "Latest weight measurement.",
                secondaryText = null,
            )
        }

        private fun bodyFatCard(
            summary: DailySummary,
            prefs: UserPreferences,
        ): CardData {
            val bodyFatPercent =
                summary.bodyFatPercent ?: return CardData(
                    title = "Body Fat",
                    value = "—",
                    unit = "%",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Body fat percentage from Health Connect",
                )

            val bodyFatStatus =
                HealthMetricsCalculator.assessBodyFatPercent(
                    bodyFatPercent,
                    prefs.age,
                    prefs.gender,
                )
            val status =
                when (bodyFatStatus) {
                    BodyFatStatus.Optimal -> MetricStatus.OPTIMAL
                    BodyFatStatus.Neutral -> MetricStatus.NEUTRAL
                    BodyFatStatus.Poor -> MetricStatus.POOR
                    BodyFatStatus.Calibrating -> MetricStatus.CALIBRATING
                }

            return CardData(
                title = "Body Fat",
                value = String.format(Locale.getDefault(), "%.1f%%", bodyFatPercent),
                unit = "",
                status = status,
                action = DashboardAction.NAVIGATE_BODY_FAT,
                tooltip = "Body fat percentage.",
            )
        }

        private fun bloodPressureCard(summary: DailySummary): CardData {
            val systolic = summary.bloodPressureSystolic ?: 0
            val diastolic = summary.bloodPressureDiastolic ?: 0

            if (systolic == 0 || diastolic == 0) {
                return CardData(
                    title = "Blood Pressure",
                    value = "—",
                    unit = "mmHg",
                    status = MetricStatus.NEUTRAL,
                    tooltip = "Blood pressure from Health Connect",
                )
            }

            val bpStatus = HealthMetricsCalculator.assessBloodPressure(systolic, diastolic)
            val status =
                when (bpStatus) {
                    BloodPressureStatus.Optimal -> MetricStatus.OPTIMAL
                    BloodPressureStatus.Neutral -> MetricStatus.NEUTRAL
                    BloodPressureStatus.HypertensionStage1 -> MetricStatus.WARNING
                    BloodPressureStatus.HypertensionStage2 -> MetricStatus.POOR
                }

            val tooltip =
                buildString {
                    append("Latest blood pressure reading.\n\n")
                    when (bpStatus) {
                        BloodPressureStatus.Optimal -> append("Optimal: <120/80 mmHg")
                        BloodPressureStatus.Neutral -> append("Elevated: 120-129/<80 mmHg")
                        BloodPressureStatus.HypertensionStage1 ->
                            append("Hypertension Stage 1: 130-139/80-89 mmHg")
                        BloodPressureStatus.HypertensionStage2 ->
                            append("Hypertension Stage 2+: ≥140/90 mmHg")
                    }
                }

            return CardData(
                title = "Blood Pressure",
                value = "$systolic/$diastolic",
                unit = "mmHg",
                status = status,
                action = DashboardAction.NAVIGATE_BLOOD_PRESSURE,
                tooltip = tooltip,
            )
        }
    }
