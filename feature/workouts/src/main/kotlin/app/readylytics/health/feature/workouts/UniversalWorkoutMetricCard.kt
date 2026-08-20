package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual

@Composable
internal fun UniversalWorkoutMetricCard(
    title: String,
    valueText: String,
    status: MetricStatus,
    tooltip: String,
    modifier: Modifier = Modifier,
    unitText: String = "",
    secondaryText: String? = null,
    rawValue: Float? = null,
    maxValue: Float = 100f,
    mode: UniversalCardDisplayMode = UniversalCardDisplayMode.VALUE,
    supportedModes: List<UniversalCardDisplayMode> = listOf(mode),
    isEditing: Boolean = false,
    onModeSelected: (UniversalCardDisplayMode) -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    UniversalMetricCard(
        presentation =
            UniversalMetricPresentation(
                title = title,
                valueText = valueText,
                unitText = unitText,
                secondaryText = secondaryText,
                status = status,
                tooltip = tooltip,
                accessibilityDescription = "$title: $valueText",
                visual =
                    if (mode == UniversalCardDisplayMode.GAUGE) {
                        UniversalMetricScalePreparer.score(rawValue, 0f, maxValue)
                    } else {
                        UniversalMetricVisual.ValueOnly
                    },
            ),
        specification =
            UniversalMetricCardSpec(
                supportedModes = supportedModes,
                usesDeltaPill = mode == UniversalCardDisplayMode.GAUGE && secondaryText != null,
            ),
        requestedMode = mode,
        isEditing = isEditing,
        onModeSelected = onModeSelected,
        modifier = modifier,
        onClick = if (isEditing) null else onClick,
    )
}
