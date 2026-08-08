package app.readylytics.health.feature.vitals.overview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.ScreenHeaderSection
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.feature.vitals.R

@Composable
fun VitalsRoute(
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    viewModel: VitalsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()

    VitalsScreen(
        uiState = uiState,
        onRangeSelected = viewModel::onRangeSelected,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onDateSelected = viewModel::onDateSelected,
        earliestDate = earliestDate,
        onNavigateToHrv = onNavigateToHrv,
        onNavigateToRhr = onNavigateToRhr,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsScreen(
    uiState: VitalsUiState,
    onRangeSelected: (TimeRange) -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onNavigateToHrv: () -> Unit,
    onNavigateToRhr: () -> Unit,
    modifier: Modifier = Modifier,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
) {
    // Single shared scroll + zoom state so all three trend charts stay in sync.
    // Keyed on selectedRange so state resets when the user switches time ranges.
    val (chartScrollState, chartZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedRange.days,
            key = "vitals-${uiState.selectedRange}",
        )
    val scrollState = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        // isRefreshing (not isLoading) gates the date-switcher: date navigation stays disabled for
        // the full sync duration, not just on true first-load (F1).
        ScreenHeaderSection(isLoading = uiState.isRefreshing) { isDisabled ->
            DateSwitcher(
                selectedDate = uiState.selectedDate,
                onPreviousDay = onPreviousDay,
                onNextDay = onNextDay,
                onDateSelected = onDateSelected,
                earliestDate = earliestDate,
                enabled = !isDisabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                        .padding(top = MaterialTheme.spacing.pageTop),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(
                        top = MaterialTheme.spacing.pageSectionGapSmall,
                        bottom = MaterialTheme.spacing.pageBottom,
                    ),
        ) {
            VitalsGaugeRow(
                isLoading = uiState.isLoading,
                presentation = uiState.presentation,
                onNavigateToHrv = onNavigateToHrv,
                onNavigateToRhr = onNavigateToRhr,
            )

            // Time Range selection
            SectionHeader(
                title = stringResource(R.string.label_physiological_trends),
                enabled = !uiState.isLoading,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                TimeRange.entries.forEachIndexed { index, range ->
                    SegmentedButton(
                        selected = uiState.selectedRange == range,
                        onClick = { onRangeSelected(range) },
                        enabled = !uiState.isLoading,
                        shape =
                            SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = TimeRange.entries.size,
                            ),
                        label = { Text(range.label) },
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

            VitalsTrendSection(
                chartInputs = uiState.chartInputs(),
                chartScrollState = chartScrollState,
                chartZoomState = chartZoomState,
                parentScrollInProgress = { scrollState.isScrollInProgress },
            )

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

            StatusLegend()
        }
    }
}
