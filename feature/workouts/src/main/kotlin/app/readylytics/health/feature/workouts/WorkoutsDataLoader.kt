package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.util.WeekBounds
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

internal class WorkoutsDataLoader(
    private val repositories: WorkoutsRepositories,
    private val useCases: WorkoutsUseCases,
    private val clock: Clock,
) {
    suspend fun loadRecentWorkouts(
        pageWorkouts: List<WorkoutData>,
        prefs: UserPreferences,
        trimpSummaries: List<DailySummary>,
    ): List<WorkoutDisplayItem> {
        val samplesByWorkoutId = fetchHeartRateSamplesByWorkout(pageWorkouts, repositories.heartRate)
        return pageWorkouts.map { workout ->
            val samples = samplesByWorkoutId[workout.id] ?: emptyList()
            val displayMetrics =
                useCases.getWorkoutDisplayMetrics.execute(
                    workout = workout,
                    samples = samples,
                    preferences = prefs,
                    historicalSummaries = trimpSummaries,
                )
            WorkoutDisplayItem(
                workout = workout,
                gainedStrain = displayMetrics.gainedStrain,
                computedTrimp = displayMetrics.computedTrimp,
                gainedStrainDisplay = displayMetrics.gainedStrainDisplay,
                classification = displayMetrics.classification,
            )
        }
    }

    suspend fun loadWorkoutOnlyGains(
        window: WorkoutsRangeWindow,
        prefs: UserPreferences,
        trimpSummaries: List<DailySummary>,
    ): List<Float> {
        if (prefs.strainLoadSourceMode != LoadSourceMode.WORKOUT_ONLY) return emptyList()
        val selectedDayWorkouts =
            repositories.workout.getInRange(window.selectedMidnightMs, window.selectedDayEndMs)
        val selectedDaySamples = fetchHeartRateSamplesByWorkout(selectedDayWorkouts, repositories.heartRate)
        return selectedDayWorkouts.mapNotNull { workout ->
            val samples = selectedDaySamples[workout.id] ?: emptyList()
            useCases.getWorkoutDisplayMetrics
                .execute(
                    workout = workout,
                    samples = samples,
                    preferences = prefs,
                    historicalSummaries = trimpSummaries,
                ).gainedStrain
        }
    }

    suspend fun loadWeeklyTraining(
        anchor: LocalDate,
        prefs: UserPreferences,
        zoneId: ZoneId,
    ): WeeklyTrainingStats {
        val fetchStart = WeekBounds.previousWeekFull(anchor, prefs.weekStartDay).start
        val fromMs = fetchStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val toMs =
            anchor
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val workouts = repositories.workout.getInRange(fromMs, toMs)
        return useCases.computeWeeklyTrainingStats.execute(workouts, anchor, prefs.weekStartDay, zoneId)
    }

    suspend fun loadResidualFatigueCurve(
        date: LocalDate,
        fatigueRange: FatigueCurveRange,
        window: WorkoutsRangeWindow,
        prefs: UserPreferences,
        zoneId: ZoneId,
    ): List<FatigueCurvePoint> {
        val config =
            ResidualFatigueConfig.clamped(
                halfLifeHours = prefs.residualFatigueHalfLifeHours,
                fatigueGain = prefs.residualFatigueGain,
            )
        val startDate = date.minusDays(fatigueRange.days.toLong() - 1L)
        val fatigueInputs = repositories.workout.getCanonicalFatigueSeed(window.selectedDayEndMs)
        return useCases.generateResidualFatigueCurve.execute(
            startDate = startDate,
            endDate = date,
            zoneId = zoneId,
            config = config,
            retainedWorkouts = fatigueInputs,
            // Never draw past the present: everything after now would be a projection.
            nowMs = clock.millis(),
        )
    }
}
