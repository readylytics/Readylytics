package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.window.Popup
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.onContainerColor
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.DashboardCardSpec
import app.readylytics.health.feature.dashboard.R
import app.readylytics.health.core.ui.R as CoreUiR

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
    // Every mode resolves the same status-derived container/content pair so the card's
    // background and title/tooltip tinting stay consistent when switching visualization modes.
    val containerColor = presentation.status.containerColor()
    val contentColor = presentation.status.onContainerColor()
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

@OptIn(ExperimentalTextApi::class)
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
            when (renderMode) {
                DashboardCardDisplayMode.GAUGE ->
                    DashboardGaugeRenderer(
                        presentation = presentation,
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

// Local, purpose-built replacement for the shared MetricTooltip: it reserves the same 48dp
// interactive footprint as DashboardDisplayModeMenu's mode-selection IconButton so the title
// row's trailing action slot never reflows between editing and viewing states, and it attaches
// the "More information" contentDescription directly to that 48dp node (rather than to a
// smaller nested icon) so the accessible/interactive target matches the visible touch target.
// The glyph itself is aligned to the box's top-end corner (not centred) so the icon stays
// pinned to the card's upper-right corner, matching the shared MetricTooltip's placement; the
// remaining 48dp box only grows the touch target downwards/inwards.
@Composable
private fun DashboardTitleInfoAction(
    description: String,
    iconTint: Color,
    modifier: Modifier = Modifier,
) {
    var showPopup by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val infoContentDescription = stringResource(id = CoreUiR.string.accessibility_more_information)

    Box(
        modifier =
            modifier
                .size(48.dp)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { showPopup = true },
                ).semantics { contentDescription = infoContentDescription },
        contentAlignment = Alignment.TopEnd,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = iconTint,
            modifier =
                Modifier
                    .size(MaterialTheme.dimens.iconMedium)
                    .testTag(DASHBOARD_TITLE_INFO_ICON_TAG),
        )

        if (showPopup) {
            Popup(
                onDismissRequest = { showPopup = false },
                alignment = Alignment.TopStart,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    modifier =
                        Modifier
                            .widthIn(max = 260.dp)
                            .padding(horizontal = MaterialTheme.spacing.extraSmall),
                ) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(MaterialTheme.spacing.smallMedium),
                    )
                }
            }
        }
    }
}
