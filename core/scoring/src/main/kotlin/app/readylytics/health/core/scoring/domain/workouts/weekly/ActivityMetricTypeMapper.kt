package app.readylytics.health.core.scoring.domain.workouts.weekly

import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.CYCLING
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.ELLIPTICAL
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.HIIT
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.HIKING
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.OTHER
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.PILATES
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.ROWING
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.RUNNING
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.STAIRS
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.STRENGTH
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.SWIMMING
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.WALKING
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType.YOGA

/**
 * Single source of truth for which metric ([ActivityMetricType.DISTANCE] or
 * [ActivityMetricType.DURATION]) represents weekly volume for a [WorkoutLayoutType]. Shared by
 * Activity Volume and Training Mix so their groupings can never drift apart.
 *
 * Outdoor, GPS-trackable activities use distance; indoor/equipment-based and mixed activities use
 * duration, since distance isn't consistently meaningful or populated for them in this app's
 * Health Connect mapping.
 */
object ActivityMetricTypeMapper {
    fun metricTypeFor(activityType: WorkoutLayoutType): ActivityMetricType =
        when (activityType) {
            RUNNING, WALKING, CYCLING, SWIMMING, HIKING -> ActivityMetricType.DISTANCE
            STRENGTH, YOGA, PILATES, ELLIPTICAL, ROWING, STAIRS, HIIT, OTHER -> ActivityMetricType.DURATION
        }
}
