package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec

import app.readylytics.health.core.designsystem.dimens
import androidx.compose.ui.res.stringResource
import app.readylytics.health.feature.dashboard.R

@Composable
fun DashboardMetricCard(
    presentation: DashboardMetricPresentation,
    specification: DashboardCardSpec,
    requestedMode: DashboardCardDisplayMode,
    renderMode: DashboardCardDisplayMode,
    isEditing: Boolean,
    onModeSelected: (DashboardCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val modeStringRes = when (renderMode) {
        DashboardCardDisplayMode.GAUGE -> R.string.mode_gauge
        DashboardCardDisplayMode.BAR -> R.string.mode_bar
        DashboardCardDisplayMode.VALUE -> R.string.mode_value
    }
    val modeContext = stringResource(id = modeStringRes)
    val contentDesc = presentation.accessibilityDescription + if (isEditing) ", $modeContext" else ""

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(MaterialTheme.dimens.cardHeight)
            .semantics(mergeDescendants = true) { contentDescription = contentDesc }
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                
                if (isEditing && specification.supportedModes.size > 1) {
                    val selectionAvailable = when (val visual = presentation.visual) {
                        is DashboardMetricVisual.Goal -> visual.selectionAvailable
                        is DashboardMetricVisual.PersonalBaseline -> visual.selectionAvailable
                        is DashboardMetricVisual.ReferenceRange -> visual.selectionAvailable
                        else -> true // Score and ValueOnly don't have this field explicitly disabling it
                    }
                    DashboardDisplayModeMenu(
                        specification = specification,
                        requestedMode = requestedMode,
                        isSelectionAvailable = selectionAvailable,
                        onModeSelected = onModeSelected
                    )
                } else if (!isEditing) {
                    app.readylytics.health.core.ui.components.MetricTooltip(
                        description = presentation.tooltip,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (renderMode) {
                    DashboardCardDisplayMode.GAUGE -> DashboardGaugeRenderer(
                        presentation = presentation,
                        animateMarker = !isEditing
                    )
                    DashboardCardDisplayMode.BAR -> DashboardBarRenderer(
                        presentation = presentation
                    )
                    DashboardCardDisplayMode.VALUE -> DashboardValueRenderer(
                        presentation = presentation
                    )
                }
            }
        }
    }
}
