package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.onContainerColor
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec
import app.readylytics.health.feature.dashboard.R
import kotlinx.coroutines.launch
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun DashboardMetricCard(
    presentation: DashboardMetricPresentation,
    specification: DashboardCardSpec,
    requestedMode: DashboardCardDisplayMode,
    isEditing: Boolean,
    onModeSelected: (DashboardCardDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val modeStringRes =
        when (requestedMode) {
            DashboardCardDisplayMode.GAUGE -> R.string.mode_gauge
            DashboardCardDisplayMode.BAR -> R.string.mode_bar
            DashboardCardDisplayMode.VALUE -> R.string.mode_value
        }
    val modeContext = stringResource(id = modeStringRes)
    val contentDesc =
        if (isEditing) {
            stringResource(R.string.semantics_edit_mode_separator, modeContext)
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
            DashboardMetricCardContent(
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
private fun DashboardMetricCardContent(
    presentation: DashboardMetricPresentation,
    specification: DashboardCardSpec,
    requestedMode: DashboardCardDisplayMode,
    isEditing: Boolean,
    cardContentDescription: String,
    contentColor: Color,
    onModeSelected: (DashboardCardDisplayMode) -> Unit,
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
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        // Trim the platform's half-leading above/below the block, and disable
                        // legacy font padding, so a two-line title's declared 24sp lineHeight
                        // drives its layout height rather than per-typeface ascent/descent.
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle =
                            LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                    ),
                color = contentColor,
                // No fixed height: trimmed line-height plus minLines/maxLines = 2 already makes
                // the intrinsic height exactly two lines, so the row never reflows between a
                // one-line and a two-line title while still growing with the user's font scale.
                modifier = Modifier.weight(1f),
                // Shared across every mode so the title row allows a two-line title.
                minLines = 2,
                maxLines = 2,
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
                DashboardTitleInfoAction(
                    description = presentation.tooltip,
                    iconTint = contentColor,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (requestedMode) {
                DashboardCardDisplayMode.GAUGE ->
                    DashboardGaugeRenderer(
                        presentation = presentation,
                        secondaryUsesPill = specification.cardId.usesDeltaPill(),
                        animateMarker = !isEditing,
                        contentColor = contentColor,
                    )
                DashboardCardDisplayMode.BAR ->
                    DashboardBarRenderer(
                        presentation = presentation,
                        secondaryUsesPill = specification.cardId.usesDeltaPill(),
                        contentColor = contentColor,
                    )
                DashboardCardDisplayMode.VALUE ->
                    DashboardValueRenderer(
                        presentation = presentation,
                        contentColor = contentColor,
                        cardId = specification.cardId,
                    )
            }
        }
    }
}

// The information action remains a dedicated 48dp semantics node so assistive technology can
// reach it independently of the metric card's value and status description.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardTitleInfoAction(
    description: String,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val infoContentDescription = stringResource(id = CoreUiR.string.accessibility_more_information)

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
                            .testTag(DASHBOARD_TITLE_INFO_ICON_TAG),
                )
            }
        }
    }
}
