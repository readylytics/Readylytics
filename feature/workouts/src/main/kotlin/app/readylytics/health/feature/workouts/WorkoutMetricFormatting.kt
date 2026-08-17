package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.readylytics.health.feature.workouts.R

@Composable
internal fun unitLabel(unit: String): String =
    when (unit) {
        "km" -> stringResource(R.string.workout_metric_distance_unit_km)
        "m" -> stringResource(R.string.workout_metric_distance_unit_m)
        "mi" -> stringResource(R.string.workout_metric_distance_unit_mi)
        "ft" -> stringResource(R.string.workout_metric_elevation_unit_ft)
        "min/km" -> stringResource(R.string.workout_metric_pace_unit_min_km)
        "min/mi" -> stringResource(R.string.workout_metric_pace_unit_min_mi)
        "km/h" -> stringResource(R.string.workout_metric_speed_unit_kmh)
        "mph" -> stringResource(R.string.workout_metric_speed_unit_mph)
        else -> unit
    }
