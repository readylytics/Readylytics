package app.readylytics.health.feature.workouts

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.di.DefaultDispatcher
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.model.RouteState
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.preferences.UserPreferencesReader
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.domain.scoring.RasCalculator
import app.readylytics.health.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.domain.sync.SyncWorkoutRouteUseCase
import app.readylytics.health.domain.util.ElevationGainCalculator
import app.readylytics.health.domain.util.PaceSpeedCalculator
import app.readylytics.health.domain.util.RouteDistanceCalculator
import app.readylytics.health.domain.util.RouteProjector
import app.readylytics.health.domain.util.RouteSimplifier
import app.readylytics.health.feature.workouts.mappers.ChartDataMapper
import app.readylytics.health.feature.workouts.mappers.DailyRasBreakdownMapper
import app.readylytics.health.feature.workouts.mappers.RecoveryMetricsMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class HeartRatePoint(
    val timestamp: Instant,
    val bpm: Int,
)

@Immutable
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
    val routeUiState: RouteUiState = RouteUiState(),
    val paceSpeedChartData: List<Pair<Double, Double>> = emptyList(),
    val elevationChartData: List<Pair<Double, Double>> = emptyList(),
    val displayElevationGainMeters: Float? = null,
    val isPaceMode: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val isLoading: Boolean = true,
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
        private val syncWorkoutRouteUseCase: SyncWorkoutRouteUseCase,
        private val savedStateHandle: SavedStateHandle,
        @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WorkoutDetailUiState())
        val uiState = _uiState.asStateFlow()

        init {
            savedStateHandle.get<String>("workoutId")?.let { id ->
                loadWorkout(id)
            }
        }

        fun onRoutePermissionResult() {
            val workoutId = savedStateHandle.get<String>("workoutId") ?: return
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                syncWorkoutRouteUseCase(workoutId)
                loadWorkout(workoutId)
            }
        }

        fun loadWorkout(workoutId: String) {
            savedStateHandle["workoutId"] = workoutId
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                var workout = workoutRepository.getById(workoutId)
                if (workout == null) {
                    _uiState.update { it.copy(isLoading = false) }
                    return@launch
                }

                if (workout.routeState == RouteState.PERMISSION_REQUIRED && hcRepo.hasExerciseRoutesPermission()) {
                    syncWorkoutRouteUseCase(workoutId)
                    workout = workoutRepository.getById(workoutId) ?: workout
                }

                withContext(defaultDispatcher) {
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
                        DailyRasBreakdownMapper.mapDailyBreakdown(
                            workoutDate,
                            thirtyDaySummaries,
                            prefs.rasSourceMode,
                        )

                    val recoveryMetrics =
                        RecoveryMetricsMapper.mapRecoveryMetrics(
                            allSamples,
                            workout.endTime,
                            endHr,
                            toleranceSeconds,
                        )

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

                    val routePoints = workoutRepository.getRoutePoints(workoutId)
                    val isPaceMode = PaceSpeedCalculator.isPaceActivity(workout.exerciseType)
                    val routeUiState: RouteUiState
                    val paceSpeedChartData: List<Pair<Double, Double>>
                    val elevationChartData: List<Pair<Double, Double>>
                    var displayElevationGainMeters: Float? = null

                    if (workout.routeState == RouteState.PERMISSION_REQUIRED) {
                        routeUiState = RouteUiState(state = RouteDataState.PermissionRequired)
                        paceSpeedChartData = emptyList()
                        elevationChartData = emptyList()
                    } else if (routePoints.isNotEmpty()) {
                        val sortedPoints = routePoints.sortedBy { it.timestampMs }
                        val projectionResult = RouteProjector.project(sortedPoints)
                        val simplifiedPoints = RouteSimplifier.simplify(projectionResult.points)

                        val maxDimension = maxOf(projectionResult.widthMeters, projectionResult.heightMeters)
                        val (scaleLabel, scaleWidthDp) =
                            if (maxDimension > 0.0) {
                                val scaleMeters =
                                    when {
                                        maxDimension >= 10000.0 -> 5000.0
                                        maxDimension >= 5000.0 -> 2000.0
                                        maxDimension >= 2000.0 -> 1000.0
                                        maxDimension >= 1000.0 -> 500.0
                                        maxDimension >= 500.0 -> 200.0
                                        maxDimension >= 200.0 -> 100.0
                                        else -> 50.0
                                    }
                                val label =
                                    if (scaleMeters >= 1000.0) {
                                        "${(scaleMeters / 1000.0).toInt()} km"
                                    } else {
                                        "${scaleMeters.toInt()} m"
                                    }
                                val widthFraction = (scaleMeters / maxDimension).coerceIn(0.1, 1.0)
                                val widthDp = (widthFraction * 120.0).toFloat().coerceIn(30f, 100f)
                                Pair(label, widthDp)
                            } else {
                                Pair("", 0f)
                            }

                        routeUiState =
                            RouteUiState(
                                state = RouteDataState.Available,
                                projectedPoints = simplifiedPoints,
                                scaleLabel = scaleLabel,
                                scaleWidthDp = scaleWidthDp,
                            )

                        var cumDistM = 0.0
                        val cumDistKmList = DoubleArray(sortedPoints.size)
                        cumDistKmList[0] = 0.0
                        for (i in 1 until sortedPoints.size) {
                            cumDistM +=
                                RouteDistanceCalculator.haversineMeters(
                                    sortedPoints[i - 1].latitude,
                                    sortedPoints[i - 1].longitude,
                                    sortedPoints[i].latitude,
                                    sortedPoints[i].longitude,
                                )
                            cumDistKmList[i] = kotlin.math.round(cumDistM) / 1000.0
                        }

                        val paceSpeedList = mutableListOf<Pair<Double, Double>>()
                        val elevationList = mutableListOf<Pair<Double, Double>>()

                        val validAltitudes =
                            ElevationGainCalculator.filterAltitudePlaceholders(
                                sortedPoints.mapNotNull { it.altitude },
                            )
                        Log.d(
                            TAG,
                            "altStats n=${validAltitudes.size} " +
                                "min=${validAltitudes.minOrNull()} " +
                                "max=${validAltitudes.maxOrNull()} " +
                                "first20=${validAltitudes.take(20)}",
                        )
                        displayElevationGainMeters =
                            if (validAltitudes.size >= 2) {
                                ElevationGainCalculator.calculateAscent(validAltitudes).toFloat()
                            } else {
                                workout.elevationGainMeters
                            }

                        if (sortedPoints.size > 1) {
                            val dt0 = (sortedPoints[1].timestampMs - sortedPoints[0].timestampMs) / 1000.0
                            val dist0 =
                                RouteDistanceCalculator.haversineMeters(
                                    sortedPoints[0].latitude,
                                    sortedPoints[0].longitude,
                                    sortedPoints[1].latitude,
                                    sortedPoints[1].longitude,
                                )
                            val speedMps0 = if (dt0 > 0) dist0 / dt0 else 0.0
                            val val0 =
                                if (isPaceMode) {
                                    PaceSpeedCalculator.speedMpsToPaceMinKm(speedMps0)
                                } else {
                                    PaceSpeedCalculator.speedMpsToSpeedKmh(speedMps0)
                                }
                            paceSpeedList.add(Pair(0.0, val0))

                            for (i in 1 until sortedPoints.size) {
                                val dt = (sortedPoints[i].timestampMs - sortedPoints[i - 1].timestampMs) / 1000.0
                                val dist =
                                    RouteDistanceCalculator.haversineMeters(
                                        sortedPoints[i - 1].latitude,
                                        sortedPoints[i - 1].longitude,
                                        sortedPoints[i].latitude,
                                        sortedPoints[i].longitude,
                                    )
                                val speedMps = if (dt > 0) dist / dt else 0.0
                                val valI =
                                    if (isPaceMode) {
                                        PaceSpeedCalculator.speedMpsToPaceMinKm(speedMps)
                                    } else {
                                        PaceSpeedCalculator.speedMpsToSpeedKmh(speedMps)
                                    }
                                paceSpeedList.add(Pair(cumDistKmList[i], valI))
                            }
                        }

                        val plausibleAltitudeSet = validAltitudes.toSet()
                        for (i in sortedPoints.indices) {
                            val alt = sortedPoints[i].altitude
                            if (alt != null && alt in plausibleAltitudeSet) {
                                elevationList.add(Pair(cumDistKmList[i], alt))
                            }
                        }

                        paceSpeedChartData = paceSpeedList
                        elevationChartData = elevationList
                    } else {
                        routeUiState = RouteUiState(state = RouteDataState.NotAvailable)
                        paceSpeedChartData = emptyList()
                        elevationChartData = emptyList()
                    }

                    Log.d(
                        TAG,
                        "id=$workoutId routeState=${workout.routeState} " +
                            "routePoints=${routePoints.size} " +
                            "storedElev=${workout.elevationGainMeters} " +
                            "displayElev=$displayElevationGainMeters",
                    )

                    _uiState.update { currentState ->
                        currentState.copy(
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
                            routeUiState = routeUiState,
                            paceSpeedChartData = paceSpeedChartData,
                            elevationChartData = elevationChartData,
                            displayElevationGainMeters = displayElevationGainMeters,
                            isPaceMode = isPaceMode,
                            unitSystem = prefs.unitSystem,
                            isLoading = false,
                        )
                    }
                }
            }
        }

    private companion object {
        const val TAG = "WorkoutDetail"
    }
}