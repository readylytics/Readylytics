package app.readylytics.health.core.model.domain.workouts

/**
 * Selectable width of the residual-fatigue curve, in whole days ending on the selected date.
 *
 * Deliberately carries no display label: the segmented-button text lives in `strings.xml` and is
 * resolved through `FatigueCurveRange.labelResId` in `feature:workouts`.
 */
enum class FatigueCurveRange(val days: Int) {
    ONE_DAY(1),
    THREE_DAYS(3),
    SEVEN_DAYS(7),
}
