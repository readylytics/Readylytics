package app.readylytics.health.core.model.domain.workouts.detail

/**
 * Every Health Connect `ExerciseSessionRecord.EXERCISE_TYPE_*` constant, as a pure-Kotlin table.
 *
 * `HealthConnectRecordConverters` persists the raw numeric id (`exerciseType.toString()`), so
 * `WorkoutData.exerciseType` holds strings such as `"2"`. This enum is what turns that id back
 * into something nameable:
 *  - [hcId] is the Health Connect constant value and the key rows are stored under.
 *  - [canonicalName] is the English label for non-UI consumers (the AI advisor prompt). UI code
 *    must use the localized `ExerciseType.displayNameResId` string resource instead.
 *  - [layoutType] is the bounded grouping that drives per-type workout detail layouts.
 *
 * Constant names mirror the Health Connect suffix exactly (`EXERCISE_TYPE_BADMINTON` → [BADMINTON]);
 * `ExerciseTypeHealthConnectDriftTest` reflects over the library constants to keep it that way.
 * The gaps in the id sequence are unassigned in the Health Connect API.
 */
enum class ExerciseType(
    val hcId: Int,
    val canonicalName: String,
    val layoutType: WorkoutLayoutType,
) {
    OTHER_WORKOUT(0, "Other workout", WorkoutLayoutType.OTHER),
    BADMINTON(2, "Badminton", WorkoutLayoutType.OTHER),
    BASEBALL(4, "Baseball", WorkoutLayoutType.OTHER),
    BASKETBALL(5, "Basketball", WorkoutLayoutType.OTHER),
    BIKING(8, "Biking", WorkoutLayoutType.CYCLING),
    BIKING_STATIONARY(9, "Stationary biking", WorkoutLayoutType.OTHER),
    BOOT_CAMP(10, "Boot camp", WorkoutLayoutType.OTHER),
    BOXING(11, "Boxing", WorkoutLayoutType.OTHER),
    CALISTHENICS(13, "Calisthenics", WorkoutLayoutType.OTHER),
    CRICKET(14, "Cricket", WorkoutLayoutType.OTHER),
    DANCING(16, "Dancing", WorkoutLayoutType.OTHER),
    ELLIPTICAL(25, "Elliptical", WorkoutLayoutType.ELLIPTICAL),
    EXERCISE_CLASS(26, "Exercise class", WorkoutLayoutType.OTHER),
    FENCING(27, "Fencing", WorkoutLayoutType.OTHER),
    FOOTBALL_AMERICAN(28, "American football", WorkoutLayoutType.OTHER),
    FOOTBALL_AUSTRALIAN(29, "Australian football", WorkoutLayoutType.OTHER),
    FRISBEE_DISC(31, "Frisbee", WorkoutLayoutType.OTHER),
    GOLF(32, "Golf", WorkoutLayoutType.OTHER),
    GUIDED_BREATHING(33, "Guided breathing", WorkoutLayoutType.OTHER),
    GYMNASTICS(34, "Gymnastics", WorkoutLayoutType.OTHER),
    HANDBALL(35, "Handball", WorkoutLayoutType.OTHER),
    HIGH_INTENSITY_INTERVAL_TRAINING(36, "HIIT", WorkoutLayoutType.HIIT),
    HIKING(37, "Hiking", WorkoutLayoutType.HIKING),
    ICE_HOCKEY(38, "Ice hockey", WorkoutLayoutType.OTHER),
    ICE_SKATING(39, "Ice skating", WorkoutLayoutType.OTHER),
    MARTIAL_ARTS(44, "Martial arts", WorkoutLayoutType.OTHER),
    PADDLING(46, "Paddling", WorkoutLayoutType.OTHER),
    PARAGLIDING(47, "Paragliding", WorkoutLayoutType.OTHER),
    PILATES(48, "Pilates", WorkoutLayoutType.PILATES),
    RACQUETBALL(50, "Racquetball", WorkoutLayoutType.OTHER),
    ROCK_CLIMBING(51, "Rock climbing", WorkoutLayoutType.OTHER),
    ROLLER_HOCKEY(52, "Roller hockey", WorkoutLayoutType.OTHER),
    ROWING(53, "Rowing", WorkoutLayoutType.ROWING),
    ROWING_MACHINE(54, "Rowing machine", WorkoutLayoutType.ROWING),
    RUGBY(55, "Rugby", WorkoutLayoutType.OTHER),
    RUNNING(56, "Running", WorkoutLayoutType.RUNNING),
    RUNNING_TREADMILL(57, "Treadmill running", WorkoutLayoutType.OTHER),
    SAILING(58, "Sailing", WorkoutLayoutType.OTHER),
    SCUBA_DIVING(59, "Scuba diving", WorkoutLayoutType.OTHER),
    SKATING(60, "Skating", WorkoutLayoutType.OTHER),
    SKIING(61, "Skiing", WorkoutLayoutType.OTHER),
    SNOWBOARDING(62, "Snowboarding", WorkoutLayoutType.OTHER),
    SNOWSHOEING(63, "Snowshoeing", WorkoutLayoutType.OTHER),
    SOCCER(64, "Soccer", WorkoutLayoutType.OTHER),
    SOFTBALL(65, "Softball", WorkoutLayoutType.OTHER),
    SQUASH(66, "Squash", WorkoutLayoutType.OTHER),
    STAIR_CLIMBING(68, "Stair climbing", WorkoutLayoutType.STAIRS),
    STAIR_CLIMBING_MACHINE(69, "Stair climbing machine", WorkoutLayoutType.STAIRS),
    STRENGTH_TRAINING(70, "Strength training", WorkoutLayoutType.STRENGTH),
    STRETCHING(71, "Stretching", WorkoutLayoutType.OTHER),
    SURFING(72, "Surfing", WorkoutLayoutType.OTHER),
    SWIMMING_OPEN_WATER(73, "Open water swimming", WorkoutLayoutType.SWIMMING),
    SWIMMING_POOL(74, "Pool swimming", WorkoutLayoutType.SWIMMING),
    TABLE_TENNIS(75, "Table tennis", WorkoutLayoutType.OTHER),
    TENNIS(76, "Tennis", WorkoutLayoutType.OTHER),
    VOLLEYBALL(78, "Volleyball", WorkoutLayoutType.OTHER),
    WALKING(79, "Walking", WorkoutLayoutType.WALKING),
    WATER_POLO(80, "Water polo", WorkoutLayoutType.OTHER),
    WEIGHTLIFTING(81, "Weightlifting", WorkoutLayoutType.OTHER),
    WHEELCHAIR(82, "Wheelchair", WorkoutLayoutType.OTHER),
    YOGA(83, "Yoga", WorkoutLayoutType.YOGA),
}
