package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * Which series the training-load chart card (Workouts tab) currently displays.
 *
 * Kept as its own tiny file rather than folded into [WorkoutsStateFactory] / [WorkoutsUiState]
 * since [WorkoutsStateFactory.kt] is already above the 400-line soft target.
 */
enum class TrainingLoadMetric {
    ACWR,
    TSB,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrainingLoadMetricToggle(
    selectedMetric: TrainingLoadMetric,
    onMetricSelected: (TrainingLoadMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        TrainingLoadMetric.entries.forEachIndexed { index, metric ->
            SegmentedButton(
                selected = selectedMetric == metric,
                onClick = { onMetricSelected(metric) },
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = TrainingLoadMetric.entries.size,
                    ),
                label = {
                    Text(
                        text =
                            stringResource(
                                if (metric == TrainingLoadMetric.ACWR) {
                                    R.string.training_load_metric_acwr
                                } else {
                                    R.string.training_load_metric_tsb_toggle
                                },
                            ),
                    )
                },
            )
        }
    }
}
