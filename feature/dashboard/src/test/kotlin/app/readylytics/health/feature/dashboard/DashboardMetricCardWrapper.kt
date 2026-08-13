package app.readylytics.health.feature.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.ModeSpec

@Composable
fun DashboardMetricCard(
    presentation: UniversalMetricPresentation,
    specification: ModeSpec,
    requestedMode: DashboardCardDisplayMode,
    isEditing: Boolean,
    onModeSelected: (DashboardCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    usesDeltaPill: Boolean = false,
) {
    UniversalMetricCard(
        presentation = presentation,
        specification = specification.toUniversalSpec(usesDeltaPill),
        requestedMode = requestedMode.toUniversalMode(),
        isEditing = isEditing,
        onModeSelected = { onModeSelected(it.toDashboardMode()) },
        modifier = modifier,
        onClick = onClick,
    )
}
