package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.preferences.UnitSystem
import app.readylytics.health.domain.repository.WorkoutData
import app.readylytics.health.domain.util.PaceSpeedCalculator
import app.readylytics.health.domain.util.UnitConverter
import app.readylytics.health.feature.workouts.R
import kotlin.math.roundToInt

@Composable
fun TrainingLoadTile(
    computedTrimp: Int?,
    workout: WorkoutData,
    modifier: Modifier = Modifier,
) {
    UniversalWorkoutMetricCard(
        title = stringResource(R.string.workout_metric_training_load),
        valueText = (computedTrimp ?: MetricFormatter.roundTrimp(workout.trimp)).toString(),
        secondaryText = stringResource(R.string.workout_metric_trimp),
        status = MetricStatus.NEUTRAL,
        tooltip = stringResource(R.string.workout_tooltip_training_load),
        modifier = modifier,
    )
}

@Composable
fun AvgPulseTile(
    workout: WorkoutData,
    modifier: Modifier = Modifier,
) {
    UniversalWorkoutMetricCard(
        title = stringResource(R.string.workout_metric_avg_pulse),
        valueText =
            if (workout.avgHr >
                0
            ) {
                workout.avgHr.roundToInt().toString()
            } else {
                stringResource(R.string.workout_metric_unavailable)
            },
        secondaryText = stringResource(R.string.workout_metric_bpm),
        status = MetricStatus.NEUTRAL,
        tooltip = stringResource(R.string.workout_tooltip_avg_pulse),
        modifier = modifier,
    )
}

@Composable
fun GainedStrainTile(
    gainedStrain: Float?,
    gainedStrainDisplay: String,
    modifier: Modifier = Modifier,
) {
    UniversalWorkoutMetricCard(
        title = stringResource(R.string.workout_metric_gained_strain),
        valueText =
            gainedStrain?.let { gainedStrainDisplay } ?: stringResource(
                R.string.workout_metric_unavailable,
            ),
        secondaryText = stringResource(R.string.workout_metric_strain),
        status = MetricStatus.NEUTRAL,
        tooltip = stringResource(R.string.workout_tooltip_gained_strain),
        modifier = modifier,
    )
}

@Composable
fun RasTile(
    ras: Float?,
    modifier: Modifier = Modifier,
) {
    UniversalWorkoutMetricCard(
        title = stringResource(R.string.workout_metric_ras),
        valueText = MetricFormatter.formatRas(ras),
        secondaryText = stringResource(R.string.workout_metric_points),
        status = MetricStatus.NEUTRAL,
        tooltip = stringResource(R.string.workout_tooltip_ras),
        modifier = modifier,
    )
}

@Composable
fun OverallLoadTile(
    classification: WorkoutLoadClassification?,
    modifier: Modifier = Modifier,
) {
    UniversalWorkoutMetricCard(
        title = stringResource(R.string.workout_metric_overall_load),
        valueText =
            classification
                ?.finalLoad
                ?.let { stringResource(R.string.workout_metric_out_of_five, it.score()) }
                ?: stringResource(R.string.workout_metric_unavailable),
        secondaryText =
            classification
                ?.finalLoad
                ?.let { stringResource(it.labelResId()) }
                ?: stringResource(R.string.workout_metric_unavailable),
        status = classification?.overallStatus() ?: MetricStatus.NEUTRAL,
        tooltip = stringResource(R.string.workout_tooltip_overall_load),
        modifier = modifier,
    )
}

@Composable
fun IntensityTile(
    classification: WorkoutLoadClassification?,
    modifier: Modifier = Modifier,
) {
    UniversalWorkoutMetricCard(
        title = stringResource(R.string.workout_metric_intensity),
        valueText =
            classification
                ?.intensity
                ?.let { stringResource(R.string.workout_metric_out_of_five, it.score()) }
                ?: stringResource(R.string.workout_metric_unavailable),
        secondaryText =
            classification
                ?.intensity
                ?.let { stringResource(it.labelResId()) }
                ?: stringResource(R.string.workout_metric_unavailable),
        status = classification?.intensity?.metricStatus() ?: MetricStatus.NEUTRAL,
        tooltip = stringResource(R.string.workout_tooltip_intensity),
        modifier = modifier,
    )
}

@Composable
fun DistanceTile(
    workout: WorkoutData,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val distanceParts =
        workout.totalDistanceMeters?.let { UnitConverter.distanceParts(it, unitSystem) }
    UniversalWorkoutMetricCard(
        title = stringResource(R.string.workout_metric_distance),
        valueText =
            distanceParts?.value
                ?: stringResource(R.string.workout_metric_unavailable),
        secondaryText = distanceParts?.unit?.let { unitLabel(it) },
        status = MetricStatus.NEUTRAL,
        tooltip = stringResource(R.string.workout_tooltip_distance),
        modifier = modifier,
    )
}

@Composable
fun AvgPaceSpeedTile(
    workout: WorkoutData,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val isPace = PaceSpeedCalculator.isPaceActivity(workout.exerciseType)
    val paceParts =
        if (isPace) {
            workout.avgSpeedKmh?.takeIf { it > 0f }?.let { kmh ->
                val paceMinKm = PaceSpeedCalculator.speedMpsToPaceMinKm(kmh / 3.6).toFloat()
                UnitConverter.paceParts(paceMinKm, unitSystem)
            }
        } else {
            workout.avgSpeedKmh?.takeIf { it > 0f }?.let { kmh ->
                UnitConverter.speedParts(kmh, unitSystem)
            }
        }
    UniversalWorkoutMetricCard(
        title =
            if (isPace) {
                stringResource(R.string.workout_metric_avg_pace)
            } else {
                stringResource(R.string.workout_metric_avg_speed)
            },
        valueText =
            paceParts?.value
                ?: stringResource(R.string.workout_metric_unavailable),
        secondaryText = paceParts?.unit?.let { unitLabel(it) },
        status = MetricStatus.NEUTRAL,
        tooltip =
            if (isPace) {
                stringResource(R.string.workout_tooltip_avg_pace)
            } else {
                stringResource(R.string.workout_tooltip_avg_speed)
            },
        modifier = modifier,
    )
}

@Composable
fun ElevationGainTile(
    elevationGainMeters: Float?,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val elevationParts =
        elevationGainMeters?.let { UnitConverter.elevationParts(it, unitSystem) }
    UniversalWorkoutMetricCard(
        title = stringResource(R.string.workout_metric_elevation_gain),
        valueText =
            elevationParts?.value
                ?: stringResource(R.string.workout_metric_unavailable),
        secondaryText = elevationParts?.unit?.let { unitLabel(it) },
        status = MetricStatus.NEUTRAL,
        tooltip = stringResource(R.string.workout_tooltip_elevation_gain),
        modifier = modifier,
    )
}
