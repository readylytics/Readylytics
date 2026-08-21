package app.readylytics.health.feature.workouts

import androidx.annotation.StringRes
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemId
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.ui.R as CoreUiR

@get:StringRes
val WorkoutDetailItemId.displayNameResId: Int
    get() =
        when (this) {
            WorkoutDetailItemId.TRAINING_LOAD -> R.string.workout_metric_training_load
            WorkoutDetailItemId.AVG_PULSE -> R.string.workout_metric_avg_pulse
            WorkoutDetailItemId.GAINED_STRAIN -> R.string.workout_metric_gained_strain
            WorkoutDetailItemId.RAS -> R.string.workout_metric_ras
            WorkoutDetailItemId.OVERALL_LOAD -> R.string.workout_metric_overall_load
            WorkoutDetailItemId.INTENSITY -> R.string.workout_metric_intensity
            WorkoutDetailItemId.DISTANCE -> R.string.workout_metric_distance
            WorkoutDetailItemId.AVG_PACE_SPEED -> R.string.workout_detail_item_pace_speed
            WorkoutDetailItemId.ELEVATION_GAIN -> R.string.workout_metric_elevation_gain
            WorkoutDetailItemId.ZONE_BREAKDOWN -> R.string.workout_zones_title
            WorkoutDetailItemId.ROUTE_CONTOUR -> R.string.workout_route_title
            WorkoutDetailItemId.PACE_SPEED_CHART -> R.string.workout_detail_item_pace_speed_chart
            WorkoutDetailItemId.ELEVATION_CHART -> R.string.workout_chart_elevation_title
            WorkoutDetailItemId.TRIMP_BREAKDOWN -> CoreUiR.string.heart_rate_title
            WorkoutDetailItemId.RECOVERY_HRR -> R.string.workout_recovery_header
        }

@get:StringRes
val WorkoutLayoutType.displayNameResId: Int
    get() =
        when (this) {
            WorkoutLayoutType.RUNNING -> R.string.workout_layout_type_running
            WorkoutLayoutType.WALKING -> R.string.workout_layout_type_walking
            WorkoutLayoutType.CYCLING -> R.string.workout_layout_type_cycling
            WorkoutLayoutType.SWIMMING -> R.string.workout_layout_type_swimming
            WorkoutLayoutType.STRENGTH -> R.string.workout_layout_type_strength
            WorkoutLayoutType.HIKING -> R.string.workout_layout_type_hiking
            WorkoutLayoutType.YOGA -> R.string.workout_layout_type_yoga
            WorkoutLayoutType.PILATES -> R.string.workout_layout_type_pilates
            WorkoutLayoutType.ELLIPTICAL -> R.string.workout_layout_type_elliptical
            WorkoutLayoutType.ROWING -> R.string.workout_layout_type_rowing
            WorkoutLayoutType.STAIRS -> R.string.workout_layout_type_stairs
            WorkoutLayoutType.HIIT -> R.string.workout_layout_type_hiit
            WorkoutLayoutType.OTHER -> R.string.workout_layout_type_other
        }
