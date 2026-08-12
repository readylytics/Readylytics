package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec

@Composable
fun DashboardMetricCard(
    presentation: UniversalMetricPresentation,
    specification: DashboardCardSpec,
    requestedMode: DashboardCardDisplayMode,
    isEditing: Boolean,
    onModeSelected: (DashboardCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    UniversalMetricCard(
        presentation = presentation,
        specification = specification.toUniversalSpec(),
        requestedMode = requestedMode.toUniversalMode(),
        isEditing = isEditing,
        onModeSelected = { onModeSelected(it.toDashboardMode()) },
        modifier = modifier,
        onClick = onClick,
    )
}
