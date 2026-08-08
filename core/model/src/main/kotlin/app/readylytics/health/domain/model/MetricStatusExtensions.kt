package app.readylytics.health.domain.model

import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.scoring.ScoringConstants
import kotlin.math.roundToInt

fun SleepSessionData.efficiencyStatus(): MetricStatus =
    if (efficiency.isNaN()) MetricStatus.POOR else efficiency.sleepEfficiencyStatus()

fun SleepSessionSummary.efficiencyStatus(): MetricStatus =
    if (efficiency?.isNaN() == true) MetricStatus.POOR else efficiency.sleepEfficiencyStatus()

fun Float?.scoreStatus(): MetricStatus =
    when {
        this == null || !this.isFinite() -> MetricStatus.CALIBRATING
        this < 40f -> MetricStatus.POOR
        this < 60f -> MetricStatus.WARNING
        this < 85f -> MetricStatus.NEUTRAL
        else -> MetricStatus.OPTIMAL
    }

fun Float?.sleepEfficiencyStatus(): MetricStatus =
    when {
        this == null || !this.isFinite() -> MetricStatus.CALIBRATING
        this < 70f -> MetricStatus.POOR
        this < 80f -> MetricStatus.WARNING
        this < 85f -> MetricStatus.NEUTRAL
        else -> MetricStatus.OPTIMAL
    }

fun Float?.circadianConsistencyStatus(): MetricStatus =
    when {
        this == null || !this.isFinite() -> MetricStatus.CALIBRATING
        this < 40f -> MetricStatus.POOR
        this < 60f -> MetricStatus.WARNING
        this < 80f -> MetricStatus.NEUTRAL
        else -> MetricStatus.OPTIMAL
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
    restingHrRatio ?: return MetricStatus.CALIBRATING
    return assessRhr(
        value = restingHeartRate,
        baseline = rhrBpm?.roundToInt(),
        optimalRatio = optimalThreshold,
        warningRatio = warningThreshold,
    ).status
}

fun DailySummary.restingHrStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus {
    restingHeartRate ?: return MetricStatus.CALIBRATING
    restingHrRatio ?: return MetricStatus.CALIBRATING
    return assessRhr(
        value = restingHeartRate,
        baseline = rhrBpm?.roundToInt(),
        optimalRatio = optimalThreshold,
        warningRatio = warningThreshold,
    ).status
}

fun DailySummary.hrvStatus(
    optimalThreshold: Float,
    warningThreshold: Float,
): MetricStatus = assessHrv(nocturnalHrv, hrvBaseline, optimalThreshold, warningThreshold).status

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

@Deprecated(
    message = "Use StepsStatusClassifier.classify(stepCount, stepGoal)",
    replaceWith = ReplaceWith("StepsStatusClassifier.classify(stepCount, stepGoal)"),
)
fun stepsStatus(
    stepCount: Int,
    stepGoal: Int,
): MetricStatus = StepsStatusClassifier.classify(stepCount, stepGoal)

fun Float.strainRatioStatus(): MetricStatus =
    when {
        this.isNaN() || this < 0.0f -> MetricStatus.CALIBRATING
        this < 0.5f -> MetricStatus.POOR
        this < 0.8f -> MetricStatus.WARNING
        this <= 1.3f -> MetricStatus.OPTIMAL
        this <= 1.5f -> MetricStatus.NEUTRAL
        this <= 2.0f -> MetricStatus.WARNING
        else -> MetricStatus.POOR
    }
