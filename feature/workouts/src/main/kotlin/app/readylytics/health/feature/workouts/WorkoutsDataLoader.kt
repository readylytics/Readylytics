package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.ResidualFatigueConfig
import app.readylytics.health.core.model.domain.util.WeekBounds
import app.readylytics.health.core.model.domain.workouts.FatigueCurvePoint
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

private const val WORKOUTS_PAGE_SIZE = 10

private data class WorkoutsPage(
    val workouts: List<WorkoutData>,
    val page: Int,
    val totalPages: Int,
)

internal class WorkoutsDataLoader(
    private val repositories: WorkoutsRepositories,
    private val useCases: WorkoutsUseCases,
    private val scoringCalculators: WorkoutsScoringCalculators,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
    /** Scoring zone + strain source: a change to either restarts the data pipeline. */
    val boundaryPreferences: Flow<Pair<ZoneId, LoadSourceMode>> =
        repositories.settings.userPreferences
            .map { it.scoringZone() to it.strainLoadSourceMode }
            .distinctUntilChanged()

    fun observeState(
        params: CombinedParams,
        zoneId: ZoneId,
    ): Flow<WorkoutsUiState> {
        val window = resolveWorkoutsRangeWindow(params.range, params.date, zoneId)
        return combine(
            observeSelectedSummary(params.date, window, zoneId),
            repositories.dailySummary.observeSince(window.fetchFromMs),
            repositories.dailySummary.observeSince(window.rasFromMs),
            repositories.settings.userPreferences,
        ) { latest, trimpSummaries, rasSummaries, prefs ->
            assembleState(params, window, zoneId, latest, trimpSummaries, rasSummaries, prefs)
        }
    }

    private fun observeSelectedSummary(
        date: LocalDate,
        window: WorkoutsRangeWindow,
        zoneId: ZoneId,
    ): Flow<DailySummary?> =
        if (date == LocalDate.now(clock.withZone(zoneId))) {
            repositories.dailySummary.observeLatest()
        } else {
            flow { emit(repositories.dailySummary.getByDate(window.selectedMidnightMs)) }.flowOn(ioDispatcher)
        }

    private suspend fun assembleState(
        params: CombinedParams,
        window: WorkoutsRangeWindow,
        zoneId: ZoneId,
        latest: DailySummary?,
        trimpSummaries: List<DailySummary>,
        rasSummaries: List<DailySummary>,
        prefs: UserPreferences,
    ): WorkoutsUiState {
        val earliestLocalDate =
            resolveEarliestLocalDate(
                prefs = prefs,
                trimpSummaries = trimpSummaries,
                zoneId = zoneId,
                getEarliestWorkoutTimestamp = repositories.workout::getEarliestWorkoutTimestamp,
            )
        val page = loadPage(window, params.page)
        return buildWorkoutsState(
            WorkoutsStateInputs(
                scoringCalculator = scoringCalculators.scoringCalculator,
                trainingStressBalanceCalculator = scoringCalculators.trainingStressBalanceCalculator,
                latestSummary = latest,
                trimpSummaries = trimpSummaries,
                rasSummaries = rasSummaries,
                prefs = prefs,
                range = params.range,
                selectedDate = params.date,
                zoneId = zoneId,
                recentWorkouts = loadRecentWorkouts(page.workouts, prefs, trimpSummaries),
                currentPage = page.page,
                totalPages = page.totalPages,
                earliestLocalDate = earliestLocalDate,
                workoutOnlyGains = loadWorkoutOnlyGains(window, prefs, trimpSummaries),
                weeklyTraining = loadWeeklyTraining(params.date, prefs, zoneId),
                hasDistancePermission = useCases.distancePermissionGate.isGranted(),
                residualFatigueCurve =
                    loadResidualFatigueCurve(
                        params.date,
                        params.fatigueRange,
                        window,
                        prefs,
                        zoneId,
                    ),
                selectedFatigueRange = params.fatigueRange,
            ),
        )
    }

    private suspend fun loadPage(
        window: WorkoutsRangeWindow,
        requestedPage: Int,
    ): WorkoutsPage {
        val totalItems = repositories.workout.countByTimeRange(window.displayFromMs, window.selectedDayEndMs)
        val totalPages = maxOf(1, (totalItems + WORKOUTS_PAGE_SIZE - 1) / WORKOUTS_PAGE_SIZE)
        val page = requestedPage.coerceIn(1, totalPages)
        val workouts =
            repositories.workout.getInRangePaged(
                window.displayFromMs,
                window.selectedDayEndMs,
                WORKOUTS_PAGE_SIZE,
                (page - 1) * WORKOUTS_PAGE_SIZE,
            )
        return WorkoutsPage(workouts, page, totalPages)
    }

    private suspend fun loadRecentWorkouts(
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

    private suspend fun loadWorkoutOnlyGains(
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

    private suspend fun loadWeeklyTraining(
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

    private suspend fun loadResidualFatigueCurve(
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
