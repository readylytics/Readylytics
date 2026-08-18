package app.readylytics.health.core.ui.components.metriccard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.core.ui.components.onContainerColor
import kotlinx.coroutines.launch

@Composable
fun UniversalMetricCard(
    presentation: UniversalMetricPresentation,
    specification: UniversalMetricCardSpec,
    requestedMode: UniversalCardDisplayMode,
    modifier: Modifier = Modifier,
    isEditing: Boolean = false,
    onModeSelected: (UniversalCardDisplayMode) -> Unit = {},
    onClick: (() -> Unit)? = null,
) {
    val modeStringRes =
        when (requestedMode) {
            UniversalCardDisplayMode.GAUGE -> app.readylytics.health.core.ui.R.string.mode_gauge
            UniversalCardDisplayMode.BAR -> app.readylytics.health.core.ui.R.string.mode_bar
            UniversalCardDisplayMode.VALUE -> app.readylytics.health.core.ui.R.string.mode_value
        }
    val modeContext = stringResource(id = modeStringRes)
    val contentDesc =
        if (isEditing) {
            stringResource(app.readylytics.health.core.ui.R.string.semantics_edit_mode_separator, modeContext)
                .let(presentation.accessibilityDescription::plus)
        } else {
            presentation.accessibilityDescription
        }
    // Every mode resolves the same status-derived container/content pair so the card's
    // background and title/tooltip tinting stay consistent when switching visualization modes.
    val containerColor = presentation.status.containerColor()
    val contentColor = presentation.status.onContainerColor()
    val cardModifier =
        modifier
            .fillMaxWidth()
            .height(MaterialTheme.dimens.cardHeight)
            .testTag(UNIVERSAL_METRIC_CARD_TAG)
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
            UniversalMetricCardContent(
                presentation = presentation,
                specification = specification,
                requestedMode = requestedMode,
                isEditing = isEditing,
                cardContentDescription = contentDesc,
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
            UniversalMetricCardContent(
                presentation = presentation,
                specification = specification,
                requestedMode = requestedMode,
                isEditing = isEditing,
                cardContentDescription = contentDesc,
                contentColor = contentColor,
                onModeSelected = onModeSelected,
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun UniversalMetricCardContent(
    presentation: UniversalMetricPresentation,
    specification: UniversalMetricCardSpec,
    requestedMode: UniversalCardDisplayMode,
    isEditing: Boolean,
    cardContentDescription: String,
    contentColor: Color,
    onModeSelected: (UniversalCardDisplayMode) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = cardContentDescription }
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (isEditing && specification.supportedModes.size > 1) {
                val selectionAvailable =
                    when (val visual = presentation.visual) {
                        is UniversalMetricVisual.Goal -> visual.selectionAvailable
                        is UniversalMetricVisual.PersonalBaseline -> visual.selectionAvailable
                        is UniversalMetricVisual.ReferenceRange -> visual.selectionAvailable
                        else -> true // Score and ValueOnly don't have this field explicitly disabling it
                    }
                Box(
                    modifier = Modifier.size(MaterialTheme.dimens.iconStandard).wrapContentSize(unbounded = true),
                    contentAlignment = Alignment.Center,
                ) {
                    UniversalDisplayModeMenu(
                        specification = specification,
                        requestedMode = requestedMode,
                        isSelectionAvailable = selectionAvailable,
                        onModeSelected = onModeSelected,
                    )
                }
            } else if (!isEditing && presentation.tooltip.isNotEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .size(
                                MaterialTheme.dimens.iconStandard,
                            ).wrapContentSize(align = Alignment.TopEnd, unbounded = true),
                ) {
                    UniversalTitleInfoAction(
                        description = presentation.tooltip,
                        iconTint = contentColor,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.smallMedium))

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (requestedMode) {
                UniversalCardDisplayMode.GAUGE ->
                    UniversalGaugeRenderer(
                        presentation = presentation,
                        secondaryUsesPill = specification.usesDeltaPill,
                        animateMarker = !isEditing,
                        contentColor = contentColor,
                    )
                UniversalCardDisplayMode.BAR ->
                    UniversalBarRenderer(
                        presentation = presentation,
                        secondaryUsesPill = specification.usesDeltaPill,
                        contentColor = contentColor,
                    )
                UniversalCardDisplayMode.VALUE ->
                    UniversalValueRenderer(
                        presentation = presentation,
                        contentColor = contentColor,
                        secondaryUsesPill = specification.usesDeltaPill,
                    )
            }
        }
    }
}

// The information action remains a dedicated 48dp semantics node so assistive technology can
// reach it independently of the metric card's value and status description.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UniversalTitleInfoAction(
    description: String,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val infoContentDescription =
        stringResource(id = app.readylytics.health.core.ui.R.string.accessibility_more_information)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(description) } },
        state = tooltipState,
    ) {
        IconButton(
            onClick = { scope.launch { tooltipState.show() } },
            modifier =
                modifier
                    .offset(x = 14.dp, y = (-14).dp)
                    .size(48.dp)
                    .semantics { contentDescription = infoContentDescription },
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = iconTint,
                    modifier =
                        Modifier
                            .size(MaterialTheme.dimens.iconMedium)
                            .testTag(UNIVERSAL_TITLE_INFO_ICON_TAG),
                )
            }
        }
    }
}

@Composable
fun UniversalDisplayModeMenu(
    specification: UniversalMetricCardSpec,
    requestedMode: UniversalCardDisplayMode,
    isSelectionAvailable: Boolean,
    onModeSelected: (UniversalCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription =
                    stringResource(
                        id = app.readylytics.health.core.ui.R.string.menu_content_description_visualization_style,
                    ),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            specification.supportedModes.forEach { mode ->
                val textRes =
                    when (mode) {
                        UniversalCardDisplayMode.GAUGE -> app.readylytics.health.core.ui.R.string.mode_gauge
                        UniversalCardDisplayMode.BAR -> app.readylytics.health.core.ui.R.string.mode_bar
                        UniversalCardDisplayMode.VALUE -> app.readylytics.health.core.ui.R.string.mode_value
                    }

                val enabled =
                    when (mode) {
                        UniversalCardDisplayMode.GAUGE, UniversalCardDisplayMode.BAR -> isSelectionAvailable
                        UniversalCardDisplayMode.VALUE -> true
                    }

                val isSelected = mode == requestedMode
                val modeName = stringResource(id = textRes)
                // A dedicated contentDescription (rather than relying on the visible text plus
                // the `selected` boolean alone) gives TalkBack a single, unambiguous announcement
                // that names the category ("Visualization style") and the selection state.
                val itemDescription =
                    if (isSelected) {
                        stringResource(
                            app.readylytics.health.core.ui.R.string.menu_item_description_mode_selected,
                            modeName,
                        )
                    } else {
                        stringResource(app.readylytics.health.core.ui.R.string.menu_item_description_mode, modeName)
                    }

                DropdownMenuItem(
                    text = { Text(modeName) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    enabled = enabled,
                    modifier =
                        Modifier.semantics {
                            selected = isSelected
                            contentDescription = itemDescription
                        },
                )
            }
        }
    }
}
