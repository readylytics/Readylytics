package app.readylytics.health.domain.model

import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.scoring.ScoringConstants

fun SleepSessionData.efficiencyStatus(): MetricStatus =
    when {
        efficiency >= 85f -> MetricStatus.OPTIMAL
        efficiency >= 80f -> MetricStatus.NEUTRAL
        efficiency >= 70f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun SleepSessionSummary.efficiencyStatus(): MetricStatus {
    val eff = efficiency ?: return MetricStatus.CALIBRATING
    return when {
        eff >= 85f -> MetricStatus.OPTIMAL
        eff >= 80f -> MetricStatus.NEUTRAL
        eff >= 70f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummary.deepSleepStatus(): MetricStatus {
    val pct = deepSleepPercent
    return when (pct) {
        null -> if (sleepDurationMinutes != null && isCalibrating) MetricStatus.CALIBRATING else MetricStatus.NO_DATA
        in 25f..30f -> MetricStatus.NEUTRAL
        in 15f..25f -> MetricStatus.OPTIMAL
        in 10f..15f -> MetricStatus.NEUTRAL
        else -> MetricStatus.WARNING
    }
}

fun DailySummary.remSleepStatus(): MetricStatus {
    val pct = remSleepPercent
    return when (pct) {
        null -> if (sleepDurationMinutes != null && isCalibrating) MetricStatus.CALIBRATING else MetricStatus.NO_DATA
        in 20f..25f -> MetricStatus.OPTIMAL
        in 15f..20f -> MetricStatus.NEUTRAL
        else -> MetricStatus.WARNING
    }
}

fun DailySummary.rhrStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val ratio = restingHrRatio ?: return MetricStatus.CALIBRATING
    val poorThreshold = warningThreshold + (warningThreshold - 1)
    return when {
        ratio <= optimalThreshold -> MetricStatus.OPTIMAL
        ratio < warningThreshold -> MetricStatus.NEUTRAL
        ratio in warningThreshold..<poorThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummary.restingHrStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    val rhr = restingHeartRate ?: return MetricStatus.CALIBRATING
    val ratio = restingHrRatio ?: return MetricStatus.CALIBRATING

    val poorThreshold = warningThreshold + (warningThreshold - 1)
    return when {
        ratio <= optimalThreshold -> MetricStatus.OPTIMAL
        ratio < warningThreshold -> MetricStatus.NEUTRAL
        ratio in warningThreshold..<poorThreshold -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun DailySummary.hrvStatus(
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

fun DailySummary.sleepDurationStatus(goalMinutes: Int): MetricStatus {
    val duration = sleepDurationMinutes
    if (duration == null || goalMinutes <= 0) return MetricStatus.CALIBRATING
    val ratio = duration.toFloat() / goalMinutes
    return when {
        ratio >= ScoringConstants.Sleep.DURATION_OPTIMAL_RATIO -> MetricStatus.OPTIMAL
        ratio >= ScoringConstants.Sleep.DURATION_NEUTRAL_RATIO -> MetricStatus.NEUTRAL
        ratio >= ScoringConstants.Sleep.DURATION_WARNING_RATIO -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun Float?.rasStatus(): MetricStatus {
    val ras = this ?: return MetricStatus.CALIBRATING
    return when {
        ras >= 100f -> MetricStatus.OPTIMAL
        ras >= 75f -> MetricStatus.NEUTRAL
        ras >= 50f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun stepsStatus(
    stepCount: Int,
    stepGoal: Int,
): MetricStatus =
    when {
        stepCount >= stepGoal -> MetricStatus.OPTIMAL
        stepCount >= stepGoal * 0.75f -> MetricStatus.NEUTRAL
        stepCount >= stepGoal * 0.5f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun bodyFatStatus(
    value: Float,
    optimalMax: Float,
): MetricStatus =
    when {
        value <= optimalMax -> MetricStatus.OPTIMAL
        value <= optimalMax * 1.15f -> MetricStatus.NEUTRAL
        value <= optimalMax * 1.30f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }

fun Float.strainRatioStatus(): MetricStatus =
    when {
        this < 0.0f -> MetricStatus.CALIBRATING
        this in 0.8f..1.3f -> MetricStatus.OPTIMAL
        this in 1.3f..1.5f -> MetricStatus.NEUTRAL
        this in 1.5f..2.0f -> MetricStatus.WARNING
        this > 2.0f -> MetricStatus.POOR
        this in 0.5f..0.8f -> MetricStatus.WARNING
        this < 0.5f -> MetricStatus.POOR
        else -> MetricStatus.CALIBRATING
    }
