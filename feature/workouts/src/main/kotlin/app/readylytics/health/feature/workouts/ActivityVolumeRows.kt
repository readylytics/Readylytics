package app.readylytics.health.feature.workouts

import app.readylytics.health.core.scoring.domain.workouts.weekly.ActivityVolume
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats

/**
 * Rows for the Activity volume section: the activity types with current-week activity, ranked by
 * this week's share of total training time (via `trainingMix`), joined with their like-for-like
 * volume comparison from `activityVolumes`. Both lists come from the same [WeeklyTrainingStats],
 * so they cannot drift apart.
 */
internal fun buildActivityVolumeRows(stats: WeeklyTrainingStats): List<ActivityVolume> =
    stats.trainingMix
        .sortedByDescending { it.durationMinutes }
        .mapNotNull { mix -> stats.activityVolumes.find { it.activityType == mix.activityType } }
