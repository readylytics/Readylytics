package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.sync.SyncWorkoutRouteUseCase
import app.readylytics.health.core.model.domain.util.ElevationGainCalculator
import app.readylytics.health.core.model.domain.util.PaceSpeedCalculator
import app.readylytics.health.core.model.domain.util.RouteDistanceCalculator
import app.readylytics.health.core.model.domain.util.RouteProjector
import app.readylytics.health.core.model.domain.util.RouteSimplifier
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.RasCalculator
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.domain.model.DomainRouteLocation
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.model.RouteState
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HealthConnectRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.domain.workouts.detail.WorkoutDetailItemId
import app.readylytics.health.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.domain.workouts.detail.WorkoutLayoutTypeMapper
import app.readylytics.health.feature.workouts.mappers.ChartDataMapper
import app.readylytics.health.feature.workouts.mappers.DailyRasBreakdownMapper
import app.readylytics.health.feature.workouts.mappers.RecoveryMetricsMapper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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

/** 1-2-5 ladder of scale-bar lengths, in metres. */
private val SCALE_BAR_STEPS_METERS =
    doubleArrayOf(
        10.0,
        20.0,
        50.0,
        100.0,
        200.0,
        500.0,
        1_000.0,
        2_000.0,
        5_000.0,
        10_000.0,
        20_000.0,
        50_000.0,
        100_000.0,
        200_000.0,
        500_000.0,
    )

/**
 * Largest 1-2-5 step that fits in half the route's longest dimension, so the bar always occupies
 * a readable 20-50% of the drawn contour instead of a hardcoded dp width.
 */
internal fun pickScaleBarMeters(maxDimensionMeters: Double): Double {
    val target = maxDimensionMeters / 2.0
    return SCALE_BAR_STEPS_METERS.lastOrNull { it <= target } ?: SCALE_BAR_STEPS_METERS.first()
}

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
    val layoutType: WorkoutLayoutType = WorkoutLayoutType.OTHER,
    val itemConfigurations: List<WorkoutDetailItemConfiguration> = SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS,
    val isManagingLayout: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
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
        private val workoutDetailLayoutRepository: WorkoutDetailLayoutRepository,
        private val savedStateHandle: SavedStateHandle,
        @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WorkoutDetailUiState())
        val uiState = _uiState.asStateFlow()

        private val layoutType = MutableStateFlow(WorkoutLayoutType.OTHER)

        private val layoutDelegate =
            LayoutManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS,
                persist = { configs -> workoutDetailLayoutRepository.updateLayout(layoutType.value, configs) },
                scope = viewModelScope,
                withVisibility = { config, visible -> config.copy(isVisible = visible) },
                withPosition = { config, pos -> config.copy(position = pos) },
            )

        init {
            savedStateHandle.get<String>("workoutId")?.let { id ->
                loadWorkout(id)
            }
        }

        init {
            viewModelScope.launch {
                layoutType
                    .flatMapLatest { type -> workoutDetailLayoutRepository.layoutFor(type) }
                    .combine(layoutDelegate.state) { stored, managementState ->
                        (managementState.pendingConfigs ?: stored) to managementState.isManaging
                    }.collect { (configs, isManaging) ->
                        _uiState.update {
                            it.copy(itemConfigurations = configs, isManagingLayout = isManaging)
                        }
                    }
            }
        }

        fun onToggleLayoutManagement() {
            if (_uiState.value.isManagingLayout) {
                layoutDelegate.saveChanges()
            } else {
                layoutDelegate.enterEditMode(_uiState.value.itemConfigurations)
            }
        }

        fun onCancelLayoutManagement() {
            layoutDelegate.cancelChanges()
        }

        fun onToggleItemVisibility(
            itemId: WorkoutDetailItemId,
            visible: Boolean,
        ) {
            layoutDelegate.onToggleVisibility(_uiState.value.itemConfigurations, itemId, visible)
        }

        fun onReorderItems(newOrder: List<WorkoutDetailItemConfiguration>) {
            layoutDelegate.onReorder(_uiState.value.itemConfigurations, newOrder)
        }

        fun onResetLayoutToDefaults() {
            layoutDelegate.onResetToDefaults()
        }

        /**
         * @param grantedRoutePoints polyline returned by the per-session consent dialog. Empty when
         * the user granted the bulk `READ_EXERCISE_ROUTES` permission instead, in which case the
         * session re-read inside [syncWorkoutRouteUseCase] carries the route.
         */
        fun onRoutePermissionResult(grantedRoutePoints: List<DomainRouteLocation> = emptyList()) {
            val workoutId = savedStateHandle.get<String>("workoutId") ?: return
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                syncWorkoutRouteUseCase(workoutId, grantedRoutePoints.takeIf { it.isNotEmpty() })
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

                layoutType.value = WorkoutLayoutTypeMapper.fromExerciseType(workout.exerciseType)

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
                                    app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
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

                        // RouteProjector normalises both axes by maxDimension, so the drawn square
                        // side == maxDimension. The bar is therefore expressed as a fraction of
                        // that side and sized against the real canvas in RouteContourCard --
                        // a fixed dp width would make the legend misstate the distance.
                        val maxDimension = maxOf(projectionResult.widthMeters, projectionResult.heightMeters)
                        val (scaleLabel, scaleWidthFraction) =
                            if (maxDimension > 0.0) {
                                val scaleMeters = pickScaleBarMeters(maxDimension)
                                val label =
                                    if (scaleMeters >= 1000.0) {
                                        "${(scaleMeters / 1000.0).toInt()} km"
                                    } else {
                                        "${scaleMeters.toInt()} m"
                                    }
                                Pair(label, (scaleMeters / maxDimension).coerceIn(0.0, 1.0).toFloat())
                            } else {
                                Pair("", 0f)
                            }

                        routeUiState =
                            RouteUiState(
                                state = RouteDataState.Available,
                                projectedPoints = simplifiedPoints,
                                scaleLabel = scaleLabel,
                                scaleWidthFraction = scaleWidthFraction,
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

                        val validAltitudes =
                            ElevationGainCalculator.filterAltitudePlaceholders(
                                sortedPoints.mapNotNull { it.altitude },
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

                        paceSpeedChartData = paceSpeedList
                        elevationChartData =
                            ElevationGainCalculator.smoothElevationProfile(
                                cumDistKmList.zip(sortedPoints.map { it.altitude }),
                            )
                    } else {
                        routeUiState = RouteUiState(state = RouteDataState.NotAvailable)
                        paceSpeedChartData = emptyList()
                        elevationChartData = emptyList()
                    }

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
                            layoutType = layoutType.value,
                            isLoading = false,
                        )
                    }
                }
            }
        }
    }
