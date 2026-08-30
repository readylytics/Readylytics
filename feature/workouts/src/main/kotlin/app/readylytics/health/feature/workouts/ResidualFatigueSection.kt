package app.readylytics.health.feature.workouts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.workouts.FatigueCurveRange
import app.readylytics.health.core.ui.components.MetricTooltip
import app.readylytics.health.core.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResidualFatigueSection(
    uiState: WorkoutsUiState,
    onRangeSelected: (FatigueCurveRange) -> Unit,
    parentScrollInProgress: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SectionHeader(
            title = stringResource(R.string.chart_residual_fatigue_curve_title),
            enabled = !uiState.isLoading,
            trailingContent = {
                MetricTooltip(
                    description = stringResource(R.string.chart_residual_fatigue_curve_description),
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        ) {
            FatigueCurveRange.entries.forEachIndexed { index, range ->
                SegmentedButton(
                    selected = uiState.selectedFatigueRange == range,
                    onClick = { onRangeSelected(range) },
                    enabled = !uiState.isLoading,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = FatigueCurveRange.entries.size),
                    label = { Text(stringResource(range.labelResId)) },
                )
            }
        }
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        ResidualFatigueCurveChart(
            points = uiState.residualFatigueCurve,
            range = uiState.selectedFatigueRange,
            zoneId = uiState.zoneId,
            isLoading = uiState.isLoading,
            parentScrollInProgress = parentScrollInProgress,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        )
    }
}
