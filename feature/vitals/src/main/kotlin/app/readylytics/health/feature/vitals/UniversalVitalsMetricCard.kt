package app.readylytics.health.feature.vitals

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer

@Composable
internal fun UniversalVitalsMetricCard(
    title: String,
    valueText: String,
    status: MetricStatus,
    tooltip: String,
    rawValue: Float?,
    maxValue: Float,
    supportedModes: List<UniversalCardDisplayMode>,
    requestedMode: UniversalCardDisplayMode,
    modifier: Modifier = Modifier,
    unitText: String = "",
    secondaryText: String? = null,
    usesDeltaPill: Boolean = secondaryText != null,
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
                accessibilityDescription = "$title: $valueText $unitText",
                visual = UniversalMetricScalePreparer.score(rawValue, 0f, maxValue),
            ),
        specification =
            UniversalMetricCardSpec(
                supportedModes = supportedModes,
                usesDeltaPill = usesDeltaPill,
            ),
        requestedMode = requestedMode,
        isEditing = isEditing,
        onModeSelected = onModeSelected,
        modifier = modifier,
        onClick = if (isEditing) null else onClick,
    )
}
