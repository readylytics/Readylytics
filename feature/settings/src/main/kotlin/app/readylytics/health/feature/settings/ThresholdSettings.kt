package app.readylytics.health.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.feature.settings.R
import kotlin.math.roundToInt

@Composable
fun ThresholdSettingsSection(
    uiState: ThresholdSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    Column {
        var hrvOptimal by remember(uiState.hrvOptimalThreshold) { mutableFloatStateOf(uiState.hrvOptimalThreshold) }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_hrv_optimal_label),
            value = hrvOptimal,
            onValueChange = { hrvOptimal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.HrvOptimalThresholdChanged(hrvOptimal)) },
            valueRange = 1.0f..1.2f,
            description = stringResource(R.string.threshold_hrv_optimal_desc),
        )

        var hrvWarning by remember(uiState.hrvWarningThreshold) { mutableFloatStateOf(uiState.hrvWarningThreshold) }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_hrv_warning_label),
            value = hrvWarning,
            onValueChange = { hrvWarning = it },
            onValueChangeFinished = { onEvent(SettingsEvent.HrvWarningThresholdChanged(hrvWarning)) },
            valueRange = 0.8f..1.0f,
            description = stringResource(R.string.threshold_hrv_warning_desc),
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        var rhrOptimal by remember(uiState.rhrOptimalThreshold) { mutableFloatStateOf(uiState.rhrOptimalThreshold) }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_rhr_optimal_label),
            value = rhrOptimal,
            onValueChange = { rhrOptimal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.RhrOptimalThresholdChanged(rhrOptimal)) },
            valueRange = 0.8f..1.0f,
            description = stringResource(R.string.threshold_rhr_optimal_desc),
        )

        var rhrWarning by remember(uiState.rhrWarningThreshold) { mutableFloatStateOf(uiState.rhrWarningThreshold) }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_rhr_warning_label),
            value = rhrWarning,
            onValueChange = { rhrWarning = it },
            onValueChangeFinished = { onEvent(SettingsEvent.RhrWarningThresholdChanged(rhrWarning)) },
            valueRange = 1.0f..1.2f,
            description = stringResource(R.string.threshold_rhr_warning_desc),
        )

        // The circadian-consistency deviation threshold is no longer a flat slider here; it is
        // derived from the physiology profile (+ optional override) in the dedicated
        // "Circadian consistency" section (CircadianThresholdSettingsSection).
        var evaluationPeriod by remember(uiState.consistencyEvaluationDays) {
            mutableFloatStateOf(uiState.consistencyEvaluationDays.toFloat())
        }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_evaluation_period_label),
            value = evaluationPeriod,
            onValueChange = { evaluationPeriod = it },
            onValueChangeFinished = {
                onEvent(
                    SettingsEvent.ConsistencyEvaluationDaysChanged(evaluationPeriod.toInt()),
                )
            },
            valueRange = 3f..14f,
            steps = 10,
            displayValue = "${evaluationPeriod.toInt()} days",
            description = stringResource(R.string.threshold_evaluation_period_desc),
        )

        var baselineWindow by remember(uiState.consistencyBaselineDays) {
            mutableFloatStateOf(uiState.consistencyBaselineDays.toFloat())
        }
        ThresholdSliderItem(
            label = stringResource(R.string.threshold_baseline_window_label),
            value = baselineWindow,
            onValueChange = { baselineWindow = it },
            onValueChangeFinished = { onEvent(SettingsEvent.ConsistencyBaselineDaysChanged(baselineWindow.toInt())) },
            valueRange = 3f..30f,
            steps = 26,
            displayValue = "${baselineWindow.toInt()} sessions",
            description = stringResource(R.string.threshold_baseline_window_desc),
        )
    }
}

@Composable
fun ActivitySettingsSection(
    stepGoal: Int,
    onEvent: (SettingsEvent) -> Unit,
) {
    var currentStepGoal by remember(stepGoal) { mutableFloatStateOf(stepGoal.toFloat()) }

    Column(
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.extraSmall,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.label_daily_step_goal), style = MaterialTheme.typography.bodyMedium)
            MetricTooltip(description = stringResource(R.string.settings_step_goal_tooltip))
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${currentStepGoal.roundToInt()} steps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = currentStepGoal,
            onValueChange = { currentStepGoal = it },
            onValueChangeFinished = { onEvent(SettingsEvent.StepGoalChanged(currentStepGoal.roundToInt())) },
            valueRange = 1000f..30000f,
            steps = 57,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Suppress("ktlint:standard:max-line-length")
@Composable
fun SleepSettingsSection(
    uiState: SleepSettingsState,
    onEvent: (SettingsEvent) -> Unit,
) {
    var sleepGoalValue by remember(uiState.goalSleepHours) {
        mutableFloatStateOf(uiState.goalSleepHours)
    }
    var coreMergeGapMinutes by remember(uiState.coreMergeGapMinutes) {
        mutableFloatStateOf(uiState.coreMergeGapMinutes.toFloat())
    }
    var supplementalCutoffMinutesOfDay by remember(uiState.supplementalCutoffMinutesOfDay) {
        mutableFloatStateOf(uiState.supplementalCutoffMinutesOfDay.toFloat())
    }
    var minimumCountedSleepSegmentMinutes by remember(uiState.minimumCountedSleepSegmentMinutes) {
        mutableFloatStateOf(uiState.minimumCountedSleepSegmentMinutes.toFloat())
    }
    var supplementalArchitectureCoveragePercent by remember(uiState.supplementalArchitectureCoveragePercent) {
        mutableFloatStateOf(uiState.supplementalArchitectureCoveragePercent.toFloat())
    }

    val minCoreMergeGap = SettingsDefaults.MIN_CORE_MERGE_GAP_MINUTES.toFloat()
    val maxCoreMergeGap = SettingsDefaults.MAX_CORE_MERGE_GAP_MINUTES.toFloat()
    val coreMergeGapRange = minCoreMergeGap..maxCoreMergeGap

    val minSupplementalCutoff = SettingsDefaults.MIN_SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY.toFloat()
    val maxSupplementalCutoff = SettingsDefaults.MAX_SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY.toFloat()
    val supplementalCutoffRange = minSupplementalCutoff..maxSupplementalCutoff

    val minSegmentMinutes = SettingsDefaults.MIN_MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES.toFloat()
    val maxSegmentMinutes = SettingsDefaults.MAX_MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES.toFloat()
    val minimumSegmentRange = minSegmentMinutes..maxSegmentMinutes

    val minCoveragePercent = SettingsDefaults.MIN_SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT.toFloat()
    val maxCoveragePercent = SettingsDefaults.MAX_SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT.toFloat()
    val architectureCoverageRange = minCoveragePercent..maxCoveragePercent

    Column {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.extraSmall,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.label_sleep_goal), style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = sleepGoalValue.toSleepHoursText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = sleepGoalValue,
                onValueChange = { sleepGoalValue = it },
                onValueChangeFinished = {
                    onEvent(SettingsEvent.GoalSleepHoursChanged(sleepGoalValue))
                },
                valueRange = 4f..12f,
                steps = 15,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        ThresholdSliderItem(
            label = stringResource(R.string.settings_sleep_core_merge_gap_label),
            value = coreMergeGapMinutes,
            onValueChange = { coreMergeGapMinutes = it },
            onValueChangeFinished = {
                onEvent(SettingsEvent.CoreMergeGapMinutesChanged(coreMergeGapMinutes.roundToInt()))
            },
            valueRange = coreMergeGapRange,
            steps =
                steppedSliderSteps(
                    min = SettingsDefaults.MIN_CORE_MERGE_GAP_MINUTES,
                    max = SettingsDefaults.MAX_CORE_MERGE_GAP_MINUTES,
                    step = SettingsDefaults.CORE_MERGE_GAP_STEP_MINUTES,
                ),
            displayValue = stringResource(R.string.settings_sleep_minutes_value, coreMergeGapMinutes.roundToInt()),
            description = stringResource(R.string.settings_sleep_core_merge_gap_tooltip),
        )

        ThresholdSliderItem(
            label = stringResource(R.string.settings_sleep_supplemental_cutoff_label),
            value = supplementalCutoffMinutesOfDay,
            onValueChange = { supplementalCutoffMinutesOfDay = it },
            onValueChangeFinished = {
                onEvent(
                    SettingsEvent.SupplementalCutoffMinutesOfDayChanged(
                        supplementalCutoffMinutesOfDay.roundToInt(),
                    ),
                )
            },
            valueRange = supplementalCutoffRange,
            steps =
                steppedSliderSteps(
                    min = SettingsDefaults.MIN_SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY,
                    max = SettingsDefaults.MAX_SUPPLEMENTAL_CUTOFF_MINUTES_OF_DAY,
                    step = SettingsDefaults.SUPPLEMENTAL_CUTOFF_STEP_MINUTES,
                ),
            displayValue =
                supplementalCutoffMinutesOfDay.roundToInt().let { minutes ->
                    stringResource(
                        R.string.settings_sleep_time_value,
                        minutes / 60,
                        minutes % 60,
                    )
                },
            description = stringResource(R.string.settings_sleep_supplemental_cutoff_tooltip),
        )

        ThresholdSliderItem(
            label = stringResource(R.string.settings_sleep_minimum_segment_label),
            value = minimumCountedSleepSegmentMinutes,
            onValueChange = { minimumCountedSleepSegmentMinutes = it },
            onValueChangeFinished = {
                onEvent(
                    SettingsEvent.MinimumCountedSleepSegmentMinutesChanged(
                        minimumCountedSleepSegmentMinutes.roundToInt(),
                    ),
                )
            },
            valueRange = minimumSegmentRange,
            steps =
                steppedSliderSteps(
                    min = SettingsDefaults.MIN_MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES,
                    max = SettingsDefaults.MAX_MINIMUM_COUNTED_SLEEP_SEGMENT_MINUTES,
                    step = SettingsDefaults.MINIMUM_COUNTED_SLEEP_SEGMENT_STEP_MINUTES,
                ),
            displayValue =
                stringResource(
                    R.string.settings_sleep_minutes_value,
                    minimumCountedSleepSegmentMinutes.roundToInt(),
                ),
            description = stringResource(R.string.settings_sleep_minimum_segment_tooltip),
        )

        ThresholdSliderItem(
            label = stringResource(R.string.settings_sleep_architecture_coverage_label),
            value = supplementalArchitectureCoveragePercent,
            onValueChange = { supplementalArchitectureCoveragePercent = it },
            onValueChangeFinished = {
                onEvent(
                    SettingsEvent.SupplementalArchitectureCoveragePercentChanged(
                        supplementalArchitectureCoveragePercent.roundToInt(),
                    ),
                )
            },
            valueRange = architectureCoverageRange,
            steps =
                steppedSliderSteps(
                    min = SettingsDefaults.MIN_SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT,
                    max = SettingsDefaults.MAX_SUPPLEMENTAL_ARCHITECTURE_COVERAGE_PERCENT,
                    step = SettingsDefaults.SUPPLEMENTAL_ARCHITECTURE_COVERAGE_STEP_PERCENT,
                ),
            displayValue =
                stringResource(
                    R.string.settings_sleep_percent_value,
                    supplementalArchitectureCoveragePercent.roundToInt(),
                ),
            description = stringResource(R.string.settings_sleep_architecture_coverage_tooltip),
        )
    }
}

@Composable
fun ThresholdSliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    description: String,
    steps: Int = ((valueRange.endInclusive - valueRange.start) * 100).roundToInt() - 1,
    displayValue: String = "${(value * 100).roundToInt()}%",
    onValueChangeFinished: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
) {
    Column(
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.medium,
                vertical = MaterialTheme.spacing.extraSmall,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            MetricTooltip(description = description)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (onReset != null) {
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(MaterialTheme.dimens.iconContainerLarge),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_reset_to_default),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun Float.toSleepHoursText(): String {
    val totalMinutes = (this * 60).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (minutes == 0) "${hours}h" else "${hours}h ${minutes}m"
}

private fun steppedSliderSteps(
    min: Int,
    max: Int,
    step: Int,
): Int = ((max - min) / step) - 1
