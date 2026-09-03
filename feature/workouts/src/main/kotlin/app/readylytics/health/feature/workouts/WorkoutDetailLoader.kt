package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.di.DefaultDispatcher
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.LoadSourceSelector
import app.readylytics.health.core.model.domain.model.RouteState
import app.readylytics.health.core.model.domain.model.WorkoutRoutePoint
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.repository.DailySummaryRepository
import app.readylytics.health.core.model.domain.repository.HeartRateRepository
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.repository.WorkoutRepository
import app.readylytics.health.core.model.domain.sync.SyncWorkoutRouteUseCase
import app.readylytics.health.core.model.domain.util.ElevationGainCalculator
import app.readylytics.health.core.model.domain.util.PaceSpeedCalculator
import app.readylytics.health.core.model.domain.util.RouteDistanceCalculator
import app.readylytics.health.core.model.domain.util.RouteProjector
import app.readylytics.health.core.model.domain.util.RouteSimplifier
import app.readylytics.health.core.scoring.domain.scoring.ComputeWorkoutTrimpUseCase
import app.readylytics.health.core.scoring.domain.scoring.GetWorkoutDisplayMetricsUseCase
import app.readylytics.health.core.scoring.domain.scoring.RasCalculator
import app.readylytics.health.core.scoring.domain.scoring.WorkoutDisplayMetrics
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.feature.workouts.mappers.ChartDataMapper
import app.readylytics.health.feature.workouts.mappers.DailyRasBreakdownMapper
import app.readylytics.health.feature.workouts.mappers.RecoveryMetrics
import app.readylytics.health.feature.workouts.mappers.RecoveryMetricsMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

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
data class WorkoutDetailData(
    val workout: WorkoutData,
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
)

@Singleton
class WorkoutDetailLoader
    @Inject
    constructor(
        private val workoutRepository: WorkoutRepository,
        private val heartRateRepository: HeartRateRepository,
        private val dailySummaryRepository: DailySummaryRepository,
        private val syncWorkoutRouteUseCase: SyncWorkoutRouteUseCase,
        private val getWorkoutDisplayMetricsUseCase: GetWorkoutDisplayMetricsUseCase,
        @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    ) {
        suspend fun load(
            workoutId: String,
            prefs: UserPreferences,
        ): WorkoutDetailData? {
            var workout = workoutRepository.getById(workoutId) ?: return null

            if (workout.routeState == RouteState.PERMISSION_REQUIRED) {
                syncWorkoutRouteUseCase.syncIfPermitted(workoutId)
                workout = workoutRepository.getById(workoutId) ?: workout
            }

            return withContext(defaultDispatcher) {
                val hrDetails = loadHeartRateDetails(workout, prefs)
                val (summary, rasBreakdown) = loadSummaryAndRasBreakdown(workout.startTime, prefs)
                val routePoints = workoutRepository.getRoutePoints(workoutId)
                val isPaceMode = PaceSpeedCalculator.isPaceActivity(workout.exerciseType)
                val routeDetails = buildRouteDetails(workout, routePoints, isPaceMode)

                WorkoutDetailData(
                    workout = workout,
                    hrSamples = hrDetails.samples,
                    hrChartData = hrDetails.chartData,
                    durationMinutes = hrDetails.durationMinutes,
                    hrr1Min = hrDetails.recoveryMetrics.hrr1Min,
                    hrr2Min = hrDetails.recoveryMetrics.hrr2Min,
                    hrr3Min = hrDetails.recoveryMetrics.hrr3Min,
                    totalRas = summary?.let { LoadSourceSelector.selectTotalRas(it, prefs.rasSourceMode) },
                    rasDailyBreakdown = rasBreakdown,
                    computedTrimp = hrDetails.displayMetrics.computedTrimp.takeIf { trimp -> trimp > 0 },
                    gainedStrain = hrDetails.displayMetrics.gainedStrain,
                    gainedStrainDisplay = hrDetails.displayMetrics.gainedStrainDisplay,
                    ras =
                        RasCalculator.calculateDailyRas(
                            hrDetails.displayMetrics.preciseTrimp,
                            prefs.rasScalingFactor,
                        ),
                    classification = hrDetails.displayMetrics.classification,
                    routeUiState = routeDetails.routeUiState,
                    paceSpeedChartData = routeDetails.paceSpeedChartData,
                    elevationChartData = routeDetails.elevationChartData,
                    displayElevationGainMeters = routeDetails.displayElevationGainMeters,
                    isPaceMode = isPaceMode,
                    unitSystem = prefs.unitSystem,
                )
            }
        }

        private suspend fun loadHeartRateDetails(
            workout: WorkoutData,
            prefs: UserPreferences,
        ): HeartRateDetails {
            val start = Instant.ofEpochMilli(workout.startTime)
            val end = Instant.ofEpochMilli(workout.endTime)
            val toleranceSeconds = prefs.hrrToleranceSeconds.toLong()
            val recoveryWindowEnd = end.plus(3, ChronoUnit.MINUTES).plusSeconds(toleranceSeconds)

            val recoveryWindow =
                heartRateRepository.getRecoveryWindowSamples(
                    start.toEpochMilli(),
                    recoveryWindowEnd.toEpochMilli(),
                )
            val allSamples =
                recoveryWindow.points
                    .map { HeartRatePoint(Instant.ofEpochMilli(it.timestampMs), it.beatsPerMinute) }
                    .distinctBy { it.timestamp }
                    .sortedBy { it.timestamp }

            val (chartData, durationMinutes) =
                ChartDataMapper.mapToChartData(allSamples, workout.startTime, workout.endTime)

            val workoutEndInstant = Instant.ofEpochMilli(workout.endTime)
            val endHr = allSamples.lastOrNull { it.timestamp <= workoutEndInstant }?.bpm

            val recoveryMetrics =
                RecoveryMetricsMapper.mapRecoveryMetrics(
                    allSamples,
                    workout.endTime,
                    endHr,
                    toleranceSeconds,
                )

            val workoutSamples = allSamples.filter { it.timestamp <= workoutEndInstant }
            val displayMetrics =
                getWorkoutDisplayMetricsUseCase.execute(
                    workout = workout,
                    samples =
                        workoutSamples.map {
                            ComputeWorkoutTrimpUseCase.HeartRateSample(
                                it.timestamp,
                                it.bpm,
                            )
                        },
                )

            return HeartRateDetails(
                samples = allSamples,
                chartData = chartData,
                durationMinutes = durationMinutes,
                recoveryMetrics = recoveryMetrics,
                displayMetrics = displayMetrics,
            )
        }

        private suspend fun loadSummaryAndRasBreakdown(
            startTimeMs: Long,
            prefs: UserPreferences,
        ): Pair<DailySummary?, List<Pair<String, Float>>> {
            val scoringZone = prefs.scoringZone()
            val workoutDate = Instant.ofEpochMilli(startTimeMs).atZone(scoringZone).toLocalDate()
            val midnight = workoutDate.atStartOfDay(scoringZone).toInstant().toEpochMilli()
            val summary = dailySummaryRepository.getByDate(midnight)

            val thirtyDaysAgo =
                workoutDate
                    .minusDays(30)
                    .atStartOfDay(scoringZone)
                    .toInstant()
                    .toEpochMilli()
            val thirtyDaySummaries = dailySummaryRepository.getSince(thirtyDaysAgo)

            val rasBreakdown =
                DailyRasBreakdownMapper.mapDailyBreakdown(
                    workoutDate,
                    thirtyDaySummaries,
                    prefs.rasSourceMode,
                )
            return summary to rasBreakdown
        }

        private fun buildRouteDetails(
            workout: WorkoutData,
            routePoints: List<WorkoutRoutePoint>,
            isPaceMode: Boolean,
        ): RouteComputationDetails {
            if (workout.routeState == RouteState.PERMISSION_REQUIRED) {
                return RouteComputationDetails(
                    routeUiState = RouteUiState(state = RouteDataState.PermissionRequired),
                    paceSpeedChartData = emptyList(),
                    elevationChartData = emptyList(),
                    displayElevationGainMeters = null,
                )
            }
            return if (routePoints.isEmpty()) {
                RouteComputationDetails(
                    routeUiState = RouteUiState(state = RouteDataState.NotAvailable),
                    paceSpeedChartData = emptyList(),
                    elevationChartData = emptyList(),
                    displayElevationGainMeters = null,
                )
            } else {
                computeAvailableRouteDetails(workout, routePoints, isPaceMode)
            }
        }

        private fun computeAvailableRouteDetails(
            workout: WorkoutData,
            routePoints: List<WorkoutRoutePoint>,
            isPaceMode: Boolean,
        ): RouteComputationDetails {
            val sortedPoints = routePoints.sortedBy { it.timestampMs }
            val projectionResult = RouteProjector.project(sortedPoints)
            val simplifiedPoints = RouteSimplifier.simplify(projectionResult.points)

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

            val routeUiState =
                RouteUiState(
                    state = RouteDataState.Available,
                    projectedPoints = simplifiedPoints,
                    scaleLabel = scaleLabel,
                    scaleWidthFraction = scaleWidthFraction,
                )

            val cumDistKmList = computeCumulativeDistances(sortedPoints)
            val paceSpeedList = computePaceSpeedList(sortedPoints, cumDistKmList, isPaceMode)

            val validAltitudes =
                ElevationGainCalculator.filterAltitudePlaceholders(
                    sortedPoints.mapNotNull { it.altitude },
                )
            val displayElevationGainMeters =
                if (validAltitudes.size >= 2) {
                    ElevationGainCalculator.calculateAscent(validAltitudes).toFloat()
                } else {
                    workout.elevationGainMeters
                }

            val elevationChartData =
                ElevationGainCalculator.smoothElevationProfile(
                    cumDistKmList.zip(sortedPoints.map { it.altitude }),
                )

            return RouteComputationDetails(
                routeUiState = routeUiState,
                paceSpeedChartData = paceSpeedList,
                elevationChartData = elevationChartData,
                displayElevationGainMeters = displayElevationGainMeters,
            )
        }

        private fun computeCumulativeDistances(sortedPoints: List<WorkoutRoutePoint>): DoubleArray {
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
            return cumDistKmList
        }

        private fun computePaceSpeedList(
            sortedPoints: List<WorkoutRoutePoint>,
            cumDistKmList: DoubleArray,
            isPaceMode: Boolean,
        ): List<Pair<Double, Double>> {
            if (sortedPoints.size <= 1) return emptyList()

            val paceSpeedList = mutableListOf<Pair<Double, Double>>()
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
            return paceSpeedList
        }
    }

private data class HeartRateDetails(
    val samples: List<HeartRatePoint>,
    val chartData: List<Pair<Double, Double>>,
    val durationMinutes: Int,
    val recoveryMetrics: RecoveryMetrics,
    val displayMetrics: WorkoutDisplayMetrics,
)

private data class RouteComputationDetails(
    val routeUiState: RouteUiState,
    val paceSpeedChartData: List<Pair<Double, Double>>,
    val elevationChartData: List<Pair<Double, Double>>,
    val displayElevationGainMeters: Float?,
)
