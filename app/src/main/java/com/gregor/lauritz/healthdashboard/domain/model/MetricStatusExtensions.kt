package com.gregor.lauritz.healthdashboard.domain.model

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants

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
    val ratio =
        restingHrRatio ?: restingHrBaseline?.let { baseline ->
            if (baseline > 0) rhr.toFloat() / baseline else null
        } ?: rhrRatio

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

fun DailySummaryEntity.paiStatus(): MetricStatus {
    val pai = totalPai ?: return MetricStatus.CALIBRATING
    return when {
        pai >= 100f -> MetricStatus.OPTIMAL
        pai >= 75f -> MetricStatus.NEUTRAL
        pai >= 50f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
}

fun stepsStatus(stepCount: Int, stepGoal: Int): MetricStatus = when {
    stepCount >= stepGoal -> MetricStatus.OPTIMAL
    stepCount >= stepGoal * 0.75f -> MetricStatus.NEUTRAL
    stepCount >= stepGoal * 0.5f -> MetricStatus.WARNING
    else -> MetricStatus.POOR
}
