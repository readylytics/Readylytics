package app.readylytics.health.feature.workouts

import androidx.annotation.StringRes
import app.readylytics.health.core.model.domain.workouts.detail.ExerciseType

/**
 * Localized label for a Health Connect exercise type.
 *
 * Exhaustive by construction: a new [ExerciseType] constant without a string resource is a
 * compile error, so the table cannot silently fall back to rendering a raw numeric id again.
 */
@get:StringRes
val ExerciseType.displayNameResId: Int
    get() =
        when (this) {
            ExerciseType.OTHER_WORKOUT -> R.string.exercise_type_other_workout
            ExerciseType.BADMINTON -> R.string.exercise_type_badminton
            ExerciseType.BASEBALL -> R.string.exercise_type_baseball
            ExerciseType.BASKETBALL -> R.string.exercise_type_basketball
            ExerciseType.BIKING -> R.string.exercise_type_biking
            ExerciseType.BIKING_STATIONARY -> R.string.exercise_type_biking_stationary
            ExerciseType.BOOT_CAMP -> R.string.exercise_type_boot_camp
            ExerciseType.BOXING -> R.string.exercise_type_boxing
            ExerciseType.CALISTHENICS -> R.string.exercise_type_calisthenics
            ExerciseType.CRICKET -> R.string.exercise_type_cricket
            ExerciseType.DANCING -> R.string.exercise_type_dancing
            ExerciseType.ELLIPTICAL -> R.string.exercise_type_elliptical
            ExerciseType.EXERCISE_CLASS -> R.string.exercise_type_exercise_class
            ExerciseType.FENCING -> R.string.exercise_type_fencing
            ExerciseType.FOOTBALL_AMERICAN -> R.string.exercise_type_football_american
            ExerciseType.FOOTBALL_AUSTRALIAN -> R.string.exercise_type_football_australian
            ExerciseType.FRISBEE_DISC -> R.string.exercise_type_frisbee_disc
            ExerciseType.GOLF -> R.string.exercise_type_golf
            ExerciseType.GUIDED_BREATHING -> R.string.exercise_type_guided_breathing
            ExerciseType.GYMNASTICS -> R.string.exercise_type_gymnastics
            ExerciseType.HANDBALL -> R.string.exercise_type_handball
            ExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING -> R.string.exercise_type_high_intensity_interval_training
            ExerciseType.HIKING -> R.string.exercise_type_hiking
            ExerciseType.ICE_HOCKEY -> R.string.exercise_type_ice_hockey
            ExerciseType.ICE_SKATING -> R.string.exercise_type_ice_skating
            ExerciseType.MARTIAL_ARTS -> R.string.exercise_type_martial_arts
            ExerciseType.PADDLING -> R.string.exercise_type_paddling
            ExerciseType.PARAGLIDING -> R.string.exercise_type_paragliding
            ExerciseType.PILATES -> R.string.exercise_type_pilates
            ExerciseType.RACQUETBALL -> R.string.exercise_type_racquetball
            ExerciseType.ROCK_CLIMBING -> R.string.exercise_type_rock_climbing
            ExerciseType.ROLLER_HOCKEY -> R.string.exercise_type_roller_hockey
            ExerciseType.ROWING -> R.string.exercise_type_rowing
            ExerciseType.ROWING_MACHINE -> R.string.exercise_type_rowing_machine
            ExerciseType.RUGBY -> R.string.exercise_type_rugby
            ExerciseType.RUNNING -> R.string.exercise_type_running
            ExerciseType.RUNNING_TREADMILL -> R.string.exercise_type_running_treadmill
            ExerciseType.SAILING -> R.string.exercise_type_sailing
            ExerciseType.SCUBA_DIVING -> R.string.exercise_type_scuba_diving
            ExerciseType.SKATING -> R.string.exercise_type_skating
            ExerciseType.SKIING -> R.string.exercise_type_skiing
            ExerciseType.SNOWBOARDING -> R.string.exercise_type_snowboarding
            ExerciseType.SNOWSHOEING -> R.string.exercise_type_snowshoeing
            ExerciseType.SOCCER -> R.string.exercise_type_soccer
            ExerciseType.SOFTBALL -> R.string.exercise_type_softball
            ExerciseType.SQUASH -> R.string.exercise_type_squash
            ExerciseType.STAIR_CLIMBING -> R.string.exercise_type_stair_climbing
            ExerciseType.STAIR_CLIMBING_MACHINE -> R.string.exercise_type_stair_climbing_machine
            ExerciseType.STRENGTH_TRAINING -> R.string.exercise_type_strength_training
            ExerciseType.STRETCHING -> R.string.exercise_type_stretching
            ExerciseType.SURFING -> R.string.exercise_type_surfing
            ExerciseType.SWIMMING_OPEN_WATER -> R.string.exercise_type_swimming_open_water
            ExerciseType.SWIMMING_POOL -> R.string.exercise_type_swimming_pool
            ExerciseType.TABLE_TENNIS -> R.string.exercise_type_table_tennis
            ExerciseType.TENNIS -> R.string.exercise_type_tennis
            ExerciseType.VOLLEYBALL -> R.string.exercise_type_volleyball
            ExerciseType.WALKING -> R.string.exercise_type_walking
            ExerciseType.WATER_POLO -> R.string.exercise_type_water_polo
            ExerciseType.WEIGHTLIFTING -> R.string.exercise_type_weightlifting
            ExerciseType.WHEELCHAIR -> R.string.exercise_type_wheelchair
            ExerciseType.YOGA -> R.string.exercise_type_yoga
        }
