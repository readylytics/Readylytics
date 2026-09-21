package app.readylytics.health.ui.scaffold

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.readylytics.health.R
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationDecision
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationExample
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationReason
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationSnapshot
import app.readylytics.health.core.model.domain.recommendation.WorkoutRecommendationState
import app.readylytics.health.core.model.domain.workouts.detail.ExerciseTypeMapper
import app.readylytics.health.feature.dashboard.DashboardUiState
import app.readylytics.health.feature.dashboard.recommendation.WorkoutRecommendationCard
import app.readylytics.health.feature.dashboard.recommendation.WorkoutRecommendationExamplePresentation
import app.readylytics.health.feature.dashboard.recommendation.WorkoutRecommendationPresentation
import app.readylytics.health.feature.workouts.displayNameResId
import app.readylytics.health.feature.workouts.icon
import kotlin.math.roundToInt

/**
 * App-owned content slot for the dashboard's workout recommendation card, following the same
 * pattern as [DashboardInsightCardContent] in DashboardNavDestinations.kt: it resolves every app
 * string resource and maps the stored [WorkoutRecommendationSnapshot] to the feature's
 * presentation types, then calls the feature's [WorkoutRecommendationCard]. This mapping lives
 * here — not in DashboardViewModel or DashboardCardFactory — precisely so the feature module never
 * needs to see `DailySummary`/`WorkoutRecommendationSnapshot` (an invalid feature -> app /
 * feature -> core-model-domain-recommendation dependency inversion) and app strings are not
 * duplicated into feature resources.
 */
@Composable
fun DashboardWorkoutRecommendationCardContent(
    uiState: DashboardUiState,
    onWorkoutClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val snapshot = uiState.summary?.workoutRecommendation
    val presentation =
        remember(snapshot) {
            buildWorkoutRecommendationPresentation(snapshot, context)
        }
    WorkoutRecommendationCard(presentation = presentation, onWorkoutClick = onWorkoutClick)
}

/**
 * Pure mapping from the stored snapshot to display text. Kept as a plain (non-`@Composable`)
 * function taking [Context] rather than using `stringResource` directly so it stays unit-testable
 * with plain Robolectric (see WorkoutRecommendationPresentationMapperTest) without needing Compose
 * test infrastructure in the app module.
 *
 * [snapshot] being `null` (never computed for this day, e.g. a pre-migration row, or a stored
 * payload that failed codec validation) is a genuinely different case from a non-null snapshot
 * whose `decision.state == CALIBRATING` (the evaluator ran and determined the user is still in the
 * 7-day calibration window) — they render distinct copy and must never be collapsed together.
 */
internal fun buildWorkoutRecommendationPresentation(
    snapshot: WorkoutRecommendationSnapshot?,
    context: Context,
): WorkoutRecommendationPresentation {
    val title = context.getString(R.string.card_title_workout_recommendation)
    val info = context.getString(R.string.workout_recommendation_info_body)

    if (snapshot == null) {
        return WorkoutRecommendationPresentation(
            title = title,
            category = context.getString(R.string.workout_recommendation_category_not_calculated),
            explanation = context.getString(R.string.workout_recommendation_explanation_not_calculated),
            info = info,
            examples = emptyList(),
        )
    }

    val decision = snapshot.decision
    return WorkoutRecommendationPresentation(
        title = title,
        category = categoryFor(decision.state, context),
        explanation = explanationFor(decision, context),
        info = info,
        examples = snapshot.examples.map { it.toPresentation(context) },
    )
}

private fun categoryFor(
    state: WorkoutRecommendationState,
    context: Context,
): String =
    when (state) {
        WorkoutRecommendationState.REST -> context.getString(R.string.workout_recommendation_category_rest)
        WorkoutRecommendationState.EASY -> context.getString(R.string.workout_recommendation_category_easy)
        WorkoutRecommendationState.HARDER -> context.getString(R.string.workout_recommendation_category_harder)
        WorkoutRecommendationState.NO_SLEEP -> context.getString(R.string.workout_recommendation_category_no_sleep)
        WorkoutRecommendationState.NO_HRV -> context.getString(R.string.workout_recommendation_category_no_hrv)
        WorkoutRecommendationState.CALIBRATING ->
            context.getString(R.string.workout_recommendation_category_calibrating)
        WorkoutRecommendationState.NO_CIRCADIAN_BASELINE ->
            context.getString(R.string.workout_recommendation_category_no_circadian_baseline)
        WorkoutRecommendationState.NO_HRV_BASELINE ->
            context.getString(R.string.workout_recommendation_category_no_hrv_baseline)
    }

private fun explanationFor(
    decision: WorkoutRecommendationDecision,
    context: Context,
): String =
    when (decision.state) {
        WorkoutRecommendationState.REST,
        WorkoutRecommendationState.EASY,
        WorkoutRecommendationState.HARDER,
        -> decision.reasons.joinToString(separator = " ") { reasonText(it, context) }
        WorkoutRecommendationState.NO_SLEEP -> context.getString(R.string.workout_recommendation_explanation_no_sleep)
        WorkoutRecommendationState.NO_HRV -> context.getString(R.string.workout_recommendation_explanation_no_hrv)
        WorkoutRecommendationState.CALIBRATING ->
            context.getString(R.string.workout_recommendation_explanation_calibrating)
        WorkoutRecommendationState.NO_CIRCADIAN_BASELINE ->
            context.getString(R.string.workout_recommendation_explanation_no_circadian_baseline)
        WorkoutRecommendationState.NO_HRV_BASELINE ->
            context.getString(R.string.workout_recommendation_explanation_no_hrv_baseline)
    }

private fun reasonText(
    reason: WorkoutRecommendationReason,
    context: Context,
): String =
    when (reason) {
        WorkoutRecommendationReason.POSSIBLE_ILLNESS ->
            context.getString(R.string.workout_recommendation_reason_possible_illness)
        WorkoutRecommendationReason.HRV_LOW -> context.getString(R.string.workout_recommendation_reason_hrv_low)
        WorkoutRecommendationReason.HRV_HIGH -> context.getString(R.string.workout_recommendation_reason_hrv_high)
        WorkoutRecommendationReason.SLEEP_LOW -> context.getString(R.string.workout_recommendation_reason_sleep_low)
        WorkoutRecommendationReason.FATIGUE_HIGH ->
            context.getString(R.string.workout_recommendation_reason_fatigue_high)
        WorkoutRecommendationReason.SLEEP_SCORE_MISSING ->
            context.getString(R.string.workout_recommendation_reason_sleep_score_missing)
        WorkoutRecommendationReason.FATIGUE_MISSING ->
            context.getString(R.string.workout_recommendation_reason_fatigue_missing)
        WorkoutRecommendationReason.WITHIN_USUAL_RANGE ->
            context.getString(R.string.workout_recommendation_reason_within_usual_range)
    }

private fun WorkoutRecommendationExample.toPresentation(context: Context): WorkoutRecommendationExamplePresentation {
    // Reuses the shared workout-type-label helper/resources (feature/workouts) rather than
    // duplicating type-label strings here. The label is per exercise type ("Badminton"); the icon
    // stays on the coarser layout grouping.
    val resolvedType = ExerciseTypeMapper.fromRaw(exerciseType)
    val layoutType = resolvedType.layoutType
    val typeLabel = context.getString(resolvedType.displayNameResId)
    val recordedSessionDescription =
        averageHr?.let { hr ->
            context.getString(
                R.string.workout_recommendation_duration_with_hr_format,
                durationMinutes,
                hr.roundToInt(),
            )
        } ?: context.getString(R.string.workout_recommendation_duration_format, durationMinutes)
    val openWorkoutLabel = context.getString(R.string.workout_recommendation_open_workout_cd, typeLabel)
    return WorkoutRecommendationExamplePresentation(
        workoutId = workoutId,
        typeLabel = typeLabel,
        recordedSessionDescription = recordedSessionDescription,
        openWorkoutLabel = openWorkoutLabel,
        activityIcon = layoutType.icon,
    )
}
