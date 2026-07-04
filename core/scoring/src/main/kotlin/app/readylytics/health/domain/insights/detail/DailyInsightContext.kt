package app.readylytics.health.domain.insights.detail

import app.readylytics.health.domain.insights.InsightContext
import app.readylytics.health.domain.model.LoadSourceSelector
import java.time.LocalDate

enum class WorkoutIntensityCategory {
    EASY,
    MODERATE,
    HARD,
}

data class DailyInsightContext(
    val date: LocalDate,
    val sleepScore: Float?,
    val sleepDurationMinutes: Int?,
    val goalSleepMinutes: Int?,
    val zLnHrv: Float?,
    val zRhr: Float?,
    val rhrDeltaBpm: Float?,
    val readinessScore: Float?,
    val yesterdayTrimp: Float?,
    val strainRatio: Float?,
    val acute7dLoad: Float?,
    val chronic28dLoad: Float?,
    val stepCount: Int?,
    val stepGoal: Int?,
    val bloodPressureSystolic: Int?,
    val bloodPressureBaselineSystolic: Float?,
    val avgSleepingSpo2: Float?,
    val weightKg: Float?,
    val previousWeightKg: Float?,
    val bedtimeOffsetMinutes: Int?,
    val lastWorkoutEndedMinutesBeforeSleep: Int?,
    val workoutDurationMinutes: Int?,
    val workoutIntensityCategory: WorkoutIntensityCategory?,
) {
    companion object {
        fun from(context: InsightContext): DailyInsightContext {
            val mode = context.prefs.strainLoadSourceMode
            val recentBeforeToday =
                context.recentDays
                    .filter { it.date < context.today.date }
                    .sortedByDescending { it.date }
            val yesterday = recentBeforeToday.firstOrNull()
            val validLoads = recentBeforeToday.mapNotNull { LoadSourceSelector.selectTrimp(it, mode) }
            val acute7dLoad =
                validLoads
                    .take(7)
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()
            val chronic28dLoad =
                validLoads
                    .take(28)
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()
            val previousWeight = recentBeforeToday.mapNotNull { it.weightKg }.firstOrNull()
            val bpBaseline =
                recentBeforeToday
                    .mapNotNull { it.bloodPressureSystolic }
                    .take(7)
                    .takeIf { it.isNotEmpty() }
                    ?.average()
                    ?.toFloat()

            return DailyInsightContext(
                date = context.today.date,
                sleepScore = context.today.sleepScore,
                sleepDurationMinutes = context.today.sleepDurationMinutes,
                goalSleepMinutes = context.goalSleepMinutes,
                zLnHrv = context.today.zLnHrv,
                zRhr = context.today.zRhr,
                rhrDeltaBpm = context.today.readinessResult.diagnostics.rhrDeltaBpm,
                readinessScore = LoadSourceSelector.selectReadiness(context.today, mode),
                yesterdayTrimp = yesterday?.let { LoadSourceSelector.selectTrimp(it, mode) },
                strainRatio = LoadSourceSelector.selectStrainRatio(context.today, mode),
                acute7dLoad = acute7dLoad,
                chronic28dLoad = chronic28dLoad,
                stepCount = context.today.stepCount,
                stepGoal = context.stepGoal,
                bloodPressureSystolic = context.today.bloodPressureSystolic,
                bloodPressureBaselineSystolic = bpBaseline,
                avgSleepingSpo2 = context.today.avgSleepingSpo2,
                weightKg = context.today.weightKg,
                previousWeightKg = previousWeight,
                bedtimeOffsetMinutes = null,
                lastWorkoutEndedMinutesBeforeSleep = null,
                workoutDurationMinutes = null,
                workoutIntensityCategory = null,
            )
        }
    }
}
