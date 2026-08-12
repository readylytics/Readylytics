package app.readylytics.health.feature.vitals

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.domain.model.MetricStatus

@Composable
internal fun UniversalVitalsMetricCard(
    title: String,
    valueText: String,
    status: MetricStatus,
    tooltip: String,
    rawValue: Float?,
    maxValue: Float,
    modifier: Modifier = Modifier,
    unitText: String = "",
    secondaryText: String? = null,
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
                supportedModes = listOf(UniversalCardDisplayMode.GAUGE),
                usesDeltaPill = secondaryText != null,
            ),
        requestedMode = UniversalCardDisplayMode.GAUGE,
        modifier = modifier,
        onClick = onClick,
    )
}
