package app.readylytics.health.domain.dashboard

import app.readylytics.health.feature.dashboard.R
import app.readylytics.health.domain.calculation.HealthMetricsCalculator
import app.readylytics.health.domain.model.BloodPressureStatus
import app.readylytics.health.domain.model.BmiStatus
import app.readylytics.health.domain.model.BodyFatStatus
import app.readylytics.health.domain.model.DailyMetrics
import app.readylytics.health.domain.model.DailyMetricsMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.model.efficiencyStatus
import app.readylytics.health.domain.model.getOrNull
import app.readylytics.health.domain.model.hrvStatus
import app.readylytics.health.domain.model.rasStatus
import app.readylytics.health.domain.model.restingHrStatus
import app.readylytics.health.domain.model.rhrStatus
import app.readylytics.health.domain.model.sleepDurationStatus
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.dashboard.CardData
import app.readylytics.health.feature.dashboard.DashboardAction
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
            val rasDailyBreakdown: List<Pair<String, Float>>,
        )

        operator fun invoke(
            summary: DailySummary?,
            prefs: UserPreferences,
            date: LocalDate,
            lastSleepSession: SleepSessionSummary?,
            rasSummaries: List<DailySummary>,
        ): Result<DashboardCards> =
            try {
                val cardDataMap = calculateCardData(summary, prefs, date, lastSleepSession)
                val rasDailyBreakdown = buildRasBreakdown(date, rasSummaries, prefs)

                Result.success(
                    DashboardCards(
                        cardDataMap = cardDataMap,
                        rasDailyBreakdown = rasDailyBreakdown,
                    ),
                )
            } catch (e: Exception) {
                Result.failure("Failed to build dashboard data", "CARD_GENERATION_ERROR")
            }

        private fun calculateCardData(
            summary: DailySummary?,
            prefs: UserPreferences,
            selectedDate: LocalDate,
            lastSleepSession: SleepSessionSummary?,
        ): Map<CardId, CardData> {
            if (summary == null) return emptyMap()

            // Canonical rounding-safe projection: ALL display rounding + baseline derivation
            // happens here, once, in DailyMetricsMapper. Card builders read its fields.
            val m = DailyMetricsMapper.toMetrics(summary, prefs)

            val mapBuilder =
                mutableMapOf<CardId, CardData>(
                    CardId.SLEEP_SCORE to sleepScoreCard(summary, m),
                    CardId.READINESS to readinessCard(summary, m),
                    CardId.SLEEP_RHR to sleepCard(summary, prefs, m),
                    CardId.HRV to hrvCard(summary, prefs, m),
                    CardId.RAS_DAILY to rasCard(m),
                    CardId.SLEEP_DURATION to sleepDurationCard(summary, prefs, lastSleepSession, m),
                    CardId.RESTING_HR to restingHrCard(summary, prefs, m),
                    CardId.SLEEP_EFFICIENCY to sleepEfficiencyCard(lastSleepSession),
                )

            val workoutMetricsResult = getWorkoutMetricsUseCase(summary, m)
            val workoutMetrics = workoutMetricsResult.getOrNull()
            workoutMetrics?.strainRatioCard?.let { mapBuilder[CardId.STRAIN_RATIO] = it }

            // Add new health metrics
            mapBuilder[CardId.WEIGHT] = weightCard(summary, prefs, m)
            mapBuilder[CardId.BODY_FAT] = bodyFatCard(summary, prefs, m)
            mapBuilder[CardId.BLOOD_PRESSURE] = bloodPressureCard(summary, m)
            mapBuilder[CardId.OXYGEN_SATURATION] = oxygenSaturationCard(summary, m)

            return mapBuilder.toMap()
        }

        private fun sleepScoreCard(
            summary: DailySummary,
            m: DailyMetrics,
        ): CardData =
            CardData(
                title = resourceProvider.getString(R.string.card_title_sleep_score),
                value = m.sleepScoreRounded?.toString() ?: "—",
                unit = "",
                status = summary.sleepScore?.let { scoreStatus(it) } ?: MetricStatus.CALIBRATING,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = resourceProvider.getString(R.string.tooltip_sleep_score),
            )

        private fun readinessCard(
            summary: DailySummary,
            m: DailyMetrics,
        ): CardData =
            CardData(
                title = resourceProvider.getString(R.string.card_title_readiness),
                value = m.readinessRounded?.toString() ?: "—",
                unit = "",
                status = m.readinessRounded?.let { scoreStatus(it.toFloat()) } ?: MetricStatus.CALIBRATING,
                action = DashboardAction.NAVIGATE_WORKOUTS,
                tooltip = resourceProvider.getString(R.string.tooltip_readiness),
            )

        private fun scoreStatus(score: Float): MetricStatus =
            when {
                score >= 85f -> MetricStatus.OPTIMAL
                score >= 60f -> MetricStatus.NEUTRAL
                score >= 40f -> MetricStatus.WARNING
                else -> MetricStatus.POOR
            }

        private fun sleepEfficiencyCard(lastSleepSession: SleepSessionSummary?): CardData {
            val efficiencyStatus = lastSleepSession?.efficiencyStatus() ?: MetricStatus.CALIBRATING
            val efficiency = lastSleepSession?.efficiency?.roundToPercentInt()?.toString() ?: "—"

            return CardData(
                title = resourceProvider.getString(R.string.card_title_sleep_efficiency),
                value =
                    if (efficiency ==
                        "—"
                    ) {
                        efficiency
                    } else {
                        resourceProvider.getString(R.string.card_efficiency_format, efficiency)
                    },
                unit = "",
                status = efficiencyStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = resourceProvider.getString(R.string.card_tooltip_sleep_efficiency),
                secondaryText = resourceProvider.getString(R.string.card_goal_sleep_efficiency),
            )
        }

        private fun rasCard(m: DailyMetrics): CardData {
            val status = m.rasRounded?.toFloat().rasStatus()
            val value = m.rasRounded?.toString() ?: "—"

            return CardData(
                title = resourceProvider.getString(R.string.card_title_ras),
                value = value,
                unit = "",
                status = status,
                action = DashboardAction.NAVIGATE_WORKOUTS,
                tooltip = resourceProvider.getString(R.string.tooltip_ras),
            )
        }

        private fun buildRasBreakdown(
            endDate: LocalDate,
            summaries: List<DailySummary>,
            prefs: UserPreferences,
        ): List<Pair<String, Float>> {
            val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            return (6 downTo 0).map { daysBack ->
                val day = endDate.minusDays(daysBack.toLong())
                val entry = summaries.firstOrNull { it.date == day }
                val ras = entry?.let { LoadSourceSelector.selectDailyRas(it, prefs.rasSourceMode) }
                day.format(fmt) to (ras ?: 0f)
            }
        }

        private fun sleepCard(
            summary: DailySummary,
            prefs: UserPreferences,
            m: DailyMetrics,
        ): CardData {
            val rhrStatus = summary.rhrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)
            val rhrBaseline = m.rhrBaselineRounded
            val rhrDiff = m.rhrBaselineDiff
            val rhrArrow = m.rhrBaselineArrow?.symbol

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
                title = resourceProvider.getString(R.string.card_title_sleep_rhr),
                value = summary.restingHeartRate?.toString() ?: "—",
                unit = resourceProvider.getString(R.string.unit_bpm),
                status = rhrStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = tooltip,
            )
        }

        private fun hrvCard(
            summary: DailySummary,
            prefs: UserPreferences,
            m: DailyMetrics,
        ): CardData {
            val hrvStatus = summary.hrvStatus(prefs.hrvOptimalThreshold, prefs.hrvWarningThreshold)
            val hrvBaseline = m.hrvBaselineRounded
            val hrvDiff = m.hrvBaselineDiff
            val hrvArrow = m.hrvBaselineArrow?.symbol

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
                    val zStr = m.zLnHrvDisplay
                    val sigmaStr = m.hrvSigmaDisplay
                    if (zStr != null && sigmaStr != null) {
                        append(resourceProvider.getString(R.string.tooltip_sleep_hrv_diagnostics, zStr, sigmaStr))
                    }
                }

            return CardData(
                title = resourceProvider.getString(R.string.card_title_hrv),
                value = summary.nocturnalHrv?.toString() ?: "—",
                unit = resourceProvider.getString(R.string.unit_ms),
                status = hrvStatus,
                action = DashboardAction.NAVIGATE_SLEEP,
                tooltip = tooltip,
            )
        }

        private fun sleepDurationCard(
            summary: DailySummary,
            prefs: UserPreferences,
            lastSleepSession: SleepSessionSummary?,
            m: DailyMetrics,
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
                title = resourceProvider.getString(R.string.card_title_sleep_duration),
                value = m.sleepDurationDisplay ?: "—",
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
            m: DailyMetrics,
        ): CardData {
            val restingHrStatus = summary.restingHrStatus(prefs.rhrOptimalThreshold, prefs.rhrWarningThreshold)

            val tooltip =
                buildString {
                    val rBaseline = m.rhrBaselineRounded
                    val diff = m.rhrBaselineDiff
                    val arrow = m.rhrBaselineArrow?.symbol
                    if (rBaseline != null && diff != null && arrow != null) {
                        append(
                            resourceProvider.getString(
                                R.string.tooltip_resting_hr_baseline,
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
                title = resourceProvider.getString(R.string.card_title_resting_hr),
                value = m.restingHeartRateRounded?.toString() ?: "—",
                unit = resourceProvider.getString(R.string.unit_bpm),
                status = restingHrStatus,
                action = DashboardAction.NAVIGATE_RHR,
                tooltip = tooltip,
            )
        }

        fun formatSleepDuration(minutes: Int?): String {
            if (minutes == null) return "—"
            val hours = minutes / 60
            val mins = minutes % 60
            return if (mins == 0) {
                resourceProvider.getString(R.string.sleep_duration_hours_only, hours)
            } else {
                resourceProvider.getString(R.string.sleep_duration_hours_minutes, hours, mins)
            }
        }

        private fun weightCard(
            summary: DailySummary,
            prefs: UserPreferences,
            m: DailyMetrics,
        ): CardData {
            val unitStr =
                if (prefs.unitSystem == UnitSystem.METRIC) {
                    resourceProvider.getString(R.string.unit_metric_kg)
                } else {
                    resourceProvider.getString(R.string.unit_imperial_lbs)
                }
            val weightKg =
                summary.weightKg ?: return CardData(
                    title = resourceProvider.getString(R.string.card_title_weight),
                    value = "—",
                    unit = unitStr,
                    status = MetricStatus.NEUTRAL,
                    tooltip = resourceProvider.getString(R.string.card_tooltip_weight_no_data),
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

            val weightValue =
                if (prefs.unitSystem == UnitSystem.METRIC) m.weightKgDisplay else m.weightLbsDisplay

            return CardData(
                title = resourceProvider.getString(R.string.card_title_weight),
                value = weightValue ?: "—",
                unit = unitStr,
                status = bmiStatus,
                action = DashboardAction.NAVIGATE_WEIGHT,
                tooltip = resourceProvider.getString(R.string.card_tooltip_weight_latest),
                secondaryText = null,
            )
        }

        private fun bodyFatCard(
            summary: DailySummary,
            prefs: UserPreferences,
            m: DailyMetrics,
        ): CardData {
            val bodyFatPercent =
                summary.bodyFatPercent ?: return CardData(
                    title = resourceProvider.getString(R.string.card_title_body_fat),
                    value = "—",
                    unit = resourceProvider.getString(R.string.unit_percent),
                    status = MetricStatus.NEUTRAL,
                    tooltip = resourceProvider.getString(R.string.card_tooltip_body_fat_no_data),
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
                title = resourceProvider.getString(R.string.card_title_body_fat),
                value = m.bodyFatDisplay ?: "—",
                unit = "",
                status = status,
                action = DashboardAction.NAVIGATE_BODY_FAT,
                tooltip = resourceProvider.getString(R.string.card_tooltip_body_fat_latest),
            )
        }

        private fun bloodPressureCard(
            summary: DailySummary,
            m: DailyMetrics,
        ): CardData {
            val systolic = summary.bloodPressureSystolic ?: 0
            val diastolic = summary.bloodPressureDiastolic ?: 0

            if (systolic == 0 || diastolic == 0) {
                return CardData(
                    title = resourceProvider.getString(R.string.card_title_blood_pressure),
                    value = "—",
                    unit = resourceProvider.getString(R.string.unit_mmHg),
                    status = MetricStatus.NEUTRAL,
                    tooltip = resourceProvider.getString(R.string.card_tooltip_bp_no_data),
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
                    append(resourceProvider.getString(R.string.card_tooltip_bp_latest))
                    when (bpStatus) {
                        BloodPressureStatus.Optimal ->
                            append(
                                resourceProvider.getString(R.string.card_bp_status_optimal),
                            )
                        BloodPressureStatus.Neutral ->
                            append(
                                resourceProvider.getString(R.string.card_bp_status_neutral),
                            )
                        BloodPressureStatus.HypertensionStage1 ->
                            append(resourceProvider.getString(R.string.card_bp_status_warning))
                        BloodPressureStatus.HypertensionStage2 ->
                            append(resourceProvider.getString(R.string.card_bp_status_poor))
                    }
                }

            return CardData(
                title = resourceProvider.getString(R.string.card_title_blood_pressure),
                value = m.bloodPressureDisplay ?: "$systolic/$diastolic",
                unit = resourceProvider.getString(R.string.unit_mmHg),
                status = status,
                action = DashboardAction.NAVIGATE_BLOOD_PRESSURE,
                tooltip = tooltip,
            )
        }

        private fun oxygenSaturationCard(
            summary: DailySummary,
            m: DailyMetrics,
        ): CardData {
            val spo2 = summary.avgSleepingSpo2
            // Single source of truth: spo2Rounded comes from DailyMetricsMapper (the same
            // canonical rounding the Vitals readout uses). The card value and Vitals readout
            // therefore always agree; chart y-axis gridline ticks are scale markers, not values.
            val roundedSpo2 = m.spo2Rounded
            if (spo2 == null || spo2 <= 0f || roundedSpo2 == null) {
                return CardData(
                    title = resourceProvider.getString(R.string.card_title_oxygen_saturation),
                    value = "—",
                    unit = resourceProvider.getString(R.string.unit_percent),
                    status = MetricStatus.CALIBRATING,
                    tooltip = resourceProvider.getString(R.string.tooltip_vitals_spo2),
                    secondaryText = resourceProvider.getString(R.string.spo2_calibrating),
                )
            }
            val (status, statusLabelRes) =
                when {
                    roundedSpo2 >= 98 -> Pair(MetricStatus.OPTIMAL, R.string.spo2_optimal)
                    roundedSpo2 >= 95 -> Pair(MetricStatus.NEUTRAL, R.string.spo2_normal)
                    roundedSpo2 >= 90 -> Pair(MetricStatus.WARNING, R.string.spo2_warning)
                    else -> Pair(MetricStatus.POOR, R.string.spo2_poor)
                }

            return CardData(
                title = resourceProvider.getString(R.string.card_title_oxygen_saturation),
                value = "$roundedSpo2",
                unit = resourceProvider.getString(R.string.unit_percent),
                status = status,
                action = DashboardAction.NAVIGATE_VITALS,
                tooltip = resourceProvider.getString(R.string.tooltip_vitals_spo2),
                secondaryText = resourceProvider.getString(statusLabelRes),
            )
        }
    }
