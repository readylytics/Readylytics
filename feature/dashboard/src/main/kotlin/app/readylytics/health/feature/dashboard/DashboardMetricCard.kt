package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.onContainerColor
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec
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
    val modeStringRes =
        when (renderMode) {
            DashboardCardDisplayMode.GAUGE -> R.string.mode_gauge
            DashboardCardDisplayMode.BAR -> R.string.mode_bar
            DashboardCardDisplayMode.VALUE -> R.string.mode_value
        }
    val modeContext = stringResource(id = modeStringRes)
    val contentDesc = presentation.accessibilityDescription + if (isEditing) ", $modeContext" else ""
    val containerColor =
        if (renderMode == DashboardCardDisplayMode.VALUE) {
            presentation.status.containerColor()
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
    val contentColor =
        if (renderMode == DashboardCardDisplayMode.VALUE) {
            presentation.status.onContainerColor()
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val cardModifier =
        modifier
            .fillMaxWidth()
            .height(MaterialTheme.dimens.cardHeight)
            .semantics(mergeDescendants = true) { contentDescription = contentDesc }
            .testTag(DASHBOARD_METRIC_CARD_TAG)
    val colors =
        CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        )

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = MaterialTheme.shapes.large,
            colors = colors,
        ) {
            DashboardMetricCardContent(
                presentation = presentation,
                specification = specification,
                requestedMode = requestedMode,
                renderMode = renderMode,
                isEditing = isEditing,
                contentColor = contentColor,
                onModeSelected = onModeSelected,
            )
        }
    } else {
        Card(
            modifier = cardModifier,
            shape = MaterialTheme.shapes.large,
            colors = colors,
        ) {
            DashboardMetricCardContent(
                presentation = presentation,
                specification = specification,
                requestedMode = requestedMode,
                renderMode = renderMode,
                isEditing = isEditing,
                contentColor = contentColor,
                onModeSelected = onModeSelected,
            )
        }
    }
}

@Composable
private fun DashboardMetricCardContent(
    presentation: DashboardMetricPresentation,
    specification: DashboardCardSpec,
    requestedMode: DashboardCardDisplayMode,
    renderMode: DashboardCardDisplayMode,
    isEditing: Boolean,
    contentColor: Color,
    onModeSelected: (DashboardCardDisplayMode) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.smallMedium,
                ),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        ) {
            Text(
                text = presentation.title,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                modifier = Modifier.weight(1f),
                minLines = if (renderMode == DashboardCardDisplayMode.VALUE) 2 else 1,
                maxLines = if (renderMode == DashboardCardDisplayMode.VALUE) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (isEditing && specification.supportedModes.size > 1) {
                val selectionAvailable =
                    when (val visual = presentation.visual) {
                        is DashboardMetricVisual.Goal -> visual.selectionAvailable
                        is DashboardMetricVisual.PersonalBaseline -> visual.selectionAvailable
                        is DashboardMetricVisual.ReferenceRange -> visual.selectionAvailable
                        else -> true // Score and ValueOnly don't have this field explicitly disabling it
                    }
                DashboardDisplayModeMenu(
                    specification = specification,
                    requestedMode = requestedMode,
                    isSelectionAvailable = selectionAvailable,
                    onModeSelected = onModeSelected,
                )
            } else if (!isEditing) {
                app.readylytics.health.core.ui.components.MetricTooltip(
                    description = presentation.tooltip,
                    iconTint = contentColor,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (renderMode) {
                DashboardCardDisplayMode.GAUGE ->
                    DashboardGaugeRenderer(
                        presentation = presentation,
                        animateMarker = !isEditing,
                    )
                DashboardCardDisplayMode.BAR ->
                    DashboardBarRenderer(
                        presentation = presentation,
                    )
                DashboardCardDisplayMode.VALUE ->
                    DashboardValueRenderer(
                        presentation = presentation,
                        contentColor = contentColor,
                    )
            }
        }
    }
}
