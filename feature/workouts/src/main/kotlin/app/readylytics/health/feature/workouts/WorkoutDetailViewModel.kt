package app.readylytics.health.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.PermissionStatus
import app.readylytics.health.domain.repository.RoutePoint
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.repository.WorkoutStats
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.RasCalculator
import app.readylytics.health.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.domain.util.ElevationGainCalculator
import app.readylytics.health.domain.util.PaceSpeedCalculator
import app.readylytics.health.domain.util.ProjectedPoint
import app.readylytics.health.domain.util.RouteProjector
import app.readylytics.health.domain.util.RouteSimplifier
import app.readylytics.health.feature.workouts.mappers.ChartDataMapper
import app.readylytics.health.feature.workouts.mappers.DailyRasBreakdownMapper
import app.readylytics.health.feature.workouts.mappers.RecoveryMetricsMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class HeartRatePoint(
    val timestamp: Instant,
    val bpm: Int,
)

sealed interface RouteDataState {
    object Loading : RouteDataState

    object NotAvailable : RouteDataState

    object PermissionRequired : RouteDataState

    object Available : RouteDataState

    object Empty : RouteDataState

    object Error : RouteDataState
}

data class RouteUiState(
    val state: RouteDataState = RouteDataState.Loading,
    val points: List<ProjectedPoint> = emptyList(),
    val scaleLabel: String = "",
    val scaleLineWidthDp: Float = 0f,
)

data class WorkoutDetailUiState(
    val workout: WorkoutData? = null,
    val hrSamples: List<HeartRatePoint> = emptyList(),
    val hrChartData: List<Pair<Double, Double>> = emptyList(),
    val durationMinutes: Int = 0,
    val hrr1Min: Int? = null,
    val hrr2Min: Int? = null,
    val hrr3Min: Int? = null,
    val totalRas: Float? = null,
    val rasDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val computedTrimp: Int? = null,
    val gainedStrain: Float? = null,
    val gainedStrainDisplay: String = "—",
    val ras: Float? = null,
    val classification: WorkoutLoadClassification? = null,
    val isLoading: Boolean = true,
    val routeUiState: RouteUiState = RouteUiState(),
    val paceSpeedChartData: List<Pair<Float, Float>> = emptyList(),
    val elevationChartData: List<Pair<Float, Float>> = emptyList(),
    val isSpeedOriented: Boolean = false,
)

@HiltViewModel
class WorkoutDetailViewModel
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        private val hcRepo: HealthConnectRepository,
        private val heartRateRepository: HeartRateRepository,
        private val dailySummaryRepository: DailySummaryRepository,
        private val settingsRepo: UserPreferencesReader,
        private val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        companion object {
            private const val EXERCISE_TYPE_CYCLING = "8"
        }

        private val _uiState = MutableStateFlow(WorkoutDetailUiState())
        val uiState = _uiState.asStateFlow()

        init {
            savedStateHandle.get<String>("workoutId")?.let { id ->
                loadWorkout(id)
            }
        }

        fun loadWorkout(workoutId: String) {
            savedStateHandle["workoutId"] = workoutId
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val workout = workoutRepository.getById(workoutId)
                if (workout == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                val start = Instant.ofEpochMilli(workout.startTime)
                val end = Instant.ofEpochMilli(workout.endTime)
                val prefs = settingsRepo.userPreferences.first()
                val toleranceSeconds = prefs.hrrToleranceSeconds.toLong()
                val recoveryWindowEnd = end.plus(3, ChronoUnit.MINUTES).plusSeconds(toleranceSeconds)

                val hcSamples =
                    hcRepo
                        .readHeartRateSamples(start, recoveryWindowEnd)
                        .asSequence()
                        .flatMap { record ->
                            record.samples.map { HeartRatePoint(it.time, it.beatsPerMinute) }
                        }.toList()
                val dbSamples =
                    heartRateRepository
                        .getByTimeRange(start.toEpochMilli(), recoveryWindowEnd.toEpochMilli())
                        .map { HeartRatePoint(Instant.ofEpochMilli(it.timestampMs), it.beatsPerMinute) }
                val allSamples =
                    (hcSamples + dbSamples)
                        .distinctBy { it.timestamp }
                        .sortedBy { it.timestamp }

                val (chartData, durationMinutes) =
                    ChartDataMapper.mapToChartData(allSamples, workout.startTime, workout.endTime)

                val workoutEndInstant = Instant.ofEpochMilli(workout.endTime)
                val endHr = allSamples.lastOrNull { it.timestamp <= workoutEndInstant }?.bpm

                val workoutDate = start.atZone(ZoneId.systemDefault()).toLocalDate()
                val midnight = workoutDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val summary = dailySummaryRepository.getByDate(midnight)

                val thirtyDaysAgo =
                    workoutDate
                        .minusDays(30)
                        .atStartOfDay(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                val thirtyDaySummaries = dailySummaryRepository.getSince(thirtyDaysAgo)

                val rasBreakdown =
                    DailyRasBreakdownMapper.mapDailyBreakdown(workoutDate, thirtyDaySummaries, prefs.rasSourceMode)

                val recoveryMetrics =
                    RecoveryMetricsMapper.mapRecoveryMetrics(allSamples, workout.endTime, endHr, toleranceSeconds)

                val workoutSamples = dbSamples.filter { it.timestamp <= workoutEndInstant }
                val displayMetrics =
                    getWorkoutDisplayMetricsUseCase.execute(
                        workout = workout,
                        samples =
                            workoutSamples.map {
                                app.readylytics.health.domain.scoring.ComputeWorkoutTrimpUseCase
                                    .HeartRateSample(
                                        it.timestamp,
                                        it.bpm,
                                    )
                            },
                    )

                _uiState.update {
                    it.copy(
                        workout = workout,
                        hrSamples = allSamples,
                        hrChartData = chartData,
                        durationMinutes = durationMinutes,
                        hrr1Min = recoveryMetrics.hrr1Min,
                        hrr2Min = recoveryMetrics.hrr2Min,
                        hrr3Min = recoveryMetrics.hrr3Min,
                        totalRas = summary?.let { LoadSourceSelector.selectTotalRas(it, prefs.rasSourceMode) },
                        rasDailyBreakdown = rasBreakdown,
                        computedTrimp = displayMetrics.computedTrimp.takeIf { trimp -> trimp > 0 },
                        gainedStrain = displayMetrics.gainedStrain,
                        gainedStrainDisplay = displayMetrics.gainedStrainDisplay,
                        ras = RasCalculator.calculateDailyRas(displayMetrics.preciseTrimp, prefs.rasScalingFactor),
                        classification = displayMetrics.classification,
                        isLoading = false,
                    )
                }
                loadRouteDetail(workout)
            }
        }

        fun loadRouteDetail(workout: WorkoutData) {
            viewModelScope.launch {
                try {
                    if (workout.routeState == "NOT_AVAILABLE") {
                        _uiState.update { it.copy(routeUiState = RouteUiState(state = RouteDataState.NotAvailable)) }
                        return@launch
                    }

                    val permissionStatus = hcRepo.checkPermissions()
                    if (permissionStatus is PermissionStatus.Missing &&
                        permissionStatus.missing.contains("android.permission.health.READ_EXERCISE_ROUTES")
                    ) {
                        _uiState.update {
                            it.copy(
                                routeUiState = RouteUiState(state = RouteDataState.PermissionRequired),
                            )
                        }
                        return@launch
                    }

                    // Fetch route points from DB first
                    val dbPoints = workoutRepository.getRoutePoints(workout.id)
                    if (dbPoints.isNotEmpty()) {
                        processAndPublishRoute(workout, dbPoints)
                    } else if (workout.routeState == "PENDING_FOREGROUND_LOAD") {
                        val hcRoute = hcRepo.readExerciseRoute(workout.id)
                        if (hcRoute != null && hcRoute.points.isNotEmpty()) {
                            val routePoints =
                                hcRoute.points.map {
                                    RoutePoint(it.latitude, it.longitude, it.altitude, it.timestampMs)
                                }

                            // Fallback calculations for stats
                            val latitudes = routePoints.map { it.latitude }.toDoubleArray()
                            val longitudes = routePoints.map { it.longitude }.toDoubleArray()
                            val altitudes = routePoints.mapNotNull { it.altitude }
                            val cumulativeDist = PaceSpeedCalculator.calculateCumulativeDistances(latitudes, longitudes)
                            val totalDistance = cumulativeDist.lastOrNull() ?: 0.0

                            val elevationGain =
                                if (altitudes.isNotEmpty()) {
                                    ElevationGainCalculator.calculateAscent(
                                        altitudes,
                                    )
                                } else {
                                    0.0
                                }

                            val elapsedMinutes = (workout.endTime - workout.startTime) / 60000.0
                            val avgSpeedKmh =
                                if (elapsedMinutes >
                                    0
                                ) {
                                    (totalDistance / 1000.0) / (elapsedMinutes / 60.0)
                                } else {
                                    0.0
                                }

                            val stats =
                                WorkoutStats(
                                    avgSpeedKmh = avgSpeedKmh.toFloat(),
                                    avgPaceMinKm = if (avgSpeedKmh > 0) (60.0 / avgSpeedKmh).toFloat() else 0f,
                                    elevationGainMeters = elevationGain.toFloat(),
                                    totalDistanceMeters = totalDistance.toFloat(),
                                )

                            workoutRepository.saveRoutePoints(workout.id, routePoints, stats)
                            processAndPublishRoute(workout, routePoints)
                        } else {
                            workoutRepository.updateRouteState(workout.id, "NOT_AVAILABLE")
                            _uiState.update {
                                it.copy(
                                    routeUiState = RouteUiState(state = RouteDataState.NotAvailable),
                                )
                            }
                        }
                    } else {
                        _uiState.update { it.copy(routeUiState = RouteUiState(state = RouteDataState.NotAvailable)) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _uiState.update { it.copy(routeUiState = RouteUiState(state = RouteDataState.Error)) }
                }
            }
        }

        private fun processAndPublishRoute(
            workout: WorkoutData,
            points: List<RoutePoint>,
        ) {
            val latitudes = points.map { it.latitude }.toDoubleArray()
            val longitudes = points.map { it.longitude }.toDoubleArray()

            val projected =
                RouteProjector.project(
                    latitudes,
                    longitudes,
                    points.map { it.altitude ?: 0.0 }.toDoubleArray(),
                    points.map { it.timestampMs }.toLongArray(),
                )
            val simplified = RouteSimplifier.simplify(projected, maxPoints = 200)

            // Reuse extracted lat/lon arrays for cumulative distance computation
            val cumulativeDist = PaceSpeedCalculator.calculateCumulativeDistances(latitudes, longitudes)

            val elevationChart =
                points.indices.mapNotNull { i ->
                    val alt = points[i].altitude ?: return@mapNotNull null
                    (cumulativeDist[i] / 1000.0).toFloat() to alt.toFloat()
                }
            val paceSpeedChart =
                points.indices.drop(1).mapNotNull { index ->
                    val elapsedMillis = points[index].timestampMs - points[index - 1].timestampMs
                    val distanceMeters = cumulativeDist[index] - cumulativeDist[index - 1]
                    if (elapsedMillis <= 0L || distanceMeters <= 0.0) {
                        return@mapNotNull null
                    }

                    val speedKmh = distanceMeters / elapsedMillis * 3_600.0
                    val chartValue =
                        if (workout.exerciseType == EXERCISE_TYPE_CYCLING) speedKmh else 60.0 / speedKmh
                    (cumulativeDist[index] / 1000.0).toFloat() to chartValue.toFloat()
                }

            _uiState.update {
                it.copy(
                    routeUiState =
                        RouteUiState(
                            state = RouteDataState.Available,
                            points = simplified,
                        ),
                    paceSpeedChartData = paceSpeedChart,
                    elevationChartData = elevationChart,
                    isSpeedOriented = workout.exerciseType == EXERCISE_TYPE_CYCLING,
                )
            }
        }
    }
