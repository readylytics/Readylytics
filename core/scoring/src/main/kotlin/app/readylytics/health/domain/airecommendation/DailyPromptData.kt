package app.readylytics.health.domain.airecommendation

import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.model.PermittedRecommendation
import java.time.LocalDate

/**
 * Pure prompt data mirroring the A-G sections of
 * `internal-docs/ai-recommendations/DAILY_PROMPT_TEMPLATE.md`. Nullable fields represent
 * unavailable source data; the formatter renders them explicitly rather than fabricating values.
 */
data class DailyPromptData(
    val date: LocalDate,
    val physiologyProfile: String?,
    val calibrationPhase: String?,
    val baselineObservationCount: Int?,
    val isCalibrating: Boolean,
    val activeTrainingLoadSource: String,
    val everydayLoadConfidence: String?,
    val advisorDataConfidence: String? = null,
    val today: TodayPromptData,
    val yesterdaySleep: YesterdaySleepPromptData?,
    val yesterdayWorkouts: List<YesterdayWorkout>,
    val loadState: LoadStatePromptData,
    val activeRecoveryFlags: List<RecoveryFlagPrompt>,
    val workoutPattern: WorkoutPatternSummary,
)

data class TodayPromptData(
    val readinessScore: Float?,
    val readinessBand: String? = null,
    val restorationScore: Float?,
    val hrvBaseline: Int?,
    val hrvMuMssd: Float?,
    val hrvSigmaMssd: Float?,
    val restingHeartRate: Int?,
    val restingHrRatio: Float?,
    val rhrSigma: Float?,
    val nocturnalHrv: Int?,
    val zLnHrv: Float?,
    val zRhr: Float?,
    val baselineCalculatedAtDate: LocalDate?,
    val todayCompletedWorkouts: Int = 0,
    val todayTrimp: Float? = null,
    val todayTrainingMinutes: Int? = null,
    val dataCurrentUntil: String? = null,
    val permittedRecommendation: PermittedRecommendation = PermittedRecommendation.UNKNOWN,
    val recommendedAction: PermittedRecommendation? = null,
)

data class YesterdaySleepPromptData(
    val sleepScore: Float?,
    val sleepDurationMinutes: Int?,
    val deepSleepPercent: Float?,
    val remSleepPercent: Float?,
    val supplementalSleepDurationMinutes: Int?,
    val napCount: Int?,
    val avgSleepingSpo2: Float?,
)

data class YesterdayWorkout(
    val workout: WorkoutData,
    val modelTrimp: Float?,
    val roundedGainedStrain: String?,
    val preciseGainedStrain: String?,
    val loadClassification: String?,
    val intensity: String?,
)

data class LoadStatePromptData(
    val acuteLoad: Float?,
    val chronicLoad: Float?,
    val strainRatio: Float?,
    val loadScore: Float?,
    val loadContext: String? = null,
    val recommendedLoad: RecommendedLoadPromptData? = null,
    val totalRasWorkoutOnly: Float?,
    val totalRasEverydayHr: Float?,
    val everydayCoverageMinutes: Int?,
)

data class RecommendedLoadPromptData(
    val qualitative: String?,
)

data class RecoveryFlagPrompt(
    val flagName: RecoveryFlag,
    val plainEnglishGloss: String,
)

data class ExerciseTypePattern(
    val exerciseType: String,
    val frequencyPerWeek: Float,
    val averageTrimp: Float?,
    val averageDurationMinutes: Float?,
    val averageLoadClassification: String?,
    val preferredDaysOfWeek: List<String>,
)

data class WorkoutPatternSummary(
    val lookbackMonths: Int,
    val totalWorkoutsInWindow: Int,
    val exerciseTypeBreakdown: List<ExerciseTypePattern>,
    val restDaysPerWeekAverage: Float,
    val mostRecentRestDayGapDays: Int,
    val currentConsecutiveTrainingDayStreak: Int,
)
