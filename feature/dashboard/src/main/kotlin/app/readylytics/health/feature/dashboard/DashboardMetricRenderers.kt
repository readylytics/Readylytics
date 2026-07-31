package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.M3MetricGauge
import app.readylytics.health.core.ui.components.gaugeColor
import app.readylytics.health.core.ui.components.metricVisualizationTrackColor
import app.readylytics.health.domain.dashboard.CardId

internal const val DASHBOARD_METRIC_CARD_TAG = "dashboard_metric_card"
internal const val DASHBOARD_GAUGE_TAG = "dashboard_metric_gauge"
internal const val DASHBOARD_BAR_TAG = "dashboard_metric_bar"
internal const val DASHBOARD_DELTA_PILL_TAG = "dashboard_metric_delta_pill"
internal const val DASHBOARD_TITLE_INFO_ICON_TAG = "dashboard_metric_title_info_icon"

internal fun DashboardMetricVisual.progressFraction(): Float? =
    when (this) {
        is DashboardMetricVisual.Score -> markerFraction
        is DashboardMetricVisual.Goal -> markerFraction
        is DashboardMetricVisual.PersonalBaseline -> markerFraction
        is DashboardMetricVisual.ReferenceRange -> markerFraction
        is DashboardMetricVisual.ValueOnly -> null
    }

@Composable
fun DashboardGaugeRenderer(
    presentation: DashboardMetricPresentation,
    animateMarker: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val markerFraction = presentation.visual.progressFraction()
    val activeColor =
        if (markerFraction == null) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            presentation.status.gaugeColor()
        }

    Column(
        modifier = modifier.fillMaxWidth().testTag(DASHBOARD_GAUGE_TAG),
    ) {
        Box(
            // Elastic: with room to spare the arc plus its centred value keep their natural
            // height, but the block yields before the fixed secondary slot below it once a
            // two-line title at a large font scale eats into the card.
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            contentAlignment = Alignment.BottomCenter,
        ) {
            M3MetricGauge(
                markerFraction = markerFraction,
                activeColor = activeColor,
                animateMarker = animateMarker,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = presentation.valueText,
                    // Same plain typography token as Value mode: no bold, no custom
                    // letter-spacing, no length-based branching.
                    style = MaterialTheme.typography.displaySmall,
                    color = activeColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = presentation.unitText.ifBlank { " " },
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color =
                        if (presentation.unitText.isNotBlank()) {
                            contentColor.copy(alpha = 0.8f)
                        } else {
                            Color.Transparent
                        },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.hairline))

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            presentation.secondaryText?.takeIf(String::isNotBlank)?.let { deltaText ->
                DashboardMetricDeltaPill(deltaText)
            }
        }
    }
}

@Composable
fun DashboardBarRenderer(
    presentation: DashboardMetricPresentation,
    secondaryUsesPill: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val progressFraction = presentation.visual.progressFraction()
    val activeColor =
        if (progressFraction == null) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            presentation.status.gaugeColor()
        }
    val trackColor = metricVisualizationTrackColor()

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        Row(
            // Elastic: the value/unit line keeps its natural height while the card has room and
            // gives way first under font-scale pressure, so the fixed-height track and the
            // secondary slot below it are never pushed out of the card.
            modifier = Modifier.weight(1f, fill = false),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            Text(
                text = presentation.valueText,
                // Same plain typography token as Value mode: no bold, no custom
                // letter-spacing, no length-based branching.
                style = MaterialTheme.typography.displaySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Baseline (not bounding-box bottom) alignment so the small unit label sits on
                // the same baseline as the much larger value, the way "56 bpm" is normally set.
                modifier = Modifier.alignByBaseline(),
            )
            if (presentation.unitText.isNotBlank()) {
                Text(
                    text = presentation.unitText,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .padding(horizontal = MaterialTheme.spacing.extraSmall)
                    .testTag(DASHBOARD_BAR_TAG),
        ) {
            val strokeWidth = 8.dp.toPx()
            val startY = size.height / 2

            drawLine(
                color = trackColor,
                start = Offset(0f, startY),
                end = Offset(size.width, startY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )

            progressFraction?.coerceIn(0f, 1f)?.takeIf { it > 0f }?.let { activeFraction ->
                drawLine(
                    color = activeColor,
                    start = Offset(0f, startY),
                    end = Offset(size.width * activeFraction, startY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Tighter than the gap above the track: with the larger displaySmall value the column's
        // fixed elements have to fit the value line's full height at font scale 1.0, and the
        // weighted value row above is the single element that yields under further pressure.
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))

        Box(
            modifier = Modifier.fillMaxWidth().height(20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            presentation.secondaryText?.takeIf(String::isNotBlank)?.let { deltaText ->
                if (secondaryUsesPill) {
                    DashboardMetricDeltaPill(deltaText)
                } else {
                    Text(
                        text = deltaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// The pill inherits the card's status-derived content colour (LocalContentColor is set by the
// Card's contentColor) instead of a fixed neutral grey, so it stays legible on a WARNING/POOR/
// OPTIMAL tinted container.
@Composable
private fun DashboardMetricDeltaPill(deltaText: String) {
    val pillContentColor = LocalContentColor.current
    Surface(
        shape = CircleShape,
        color = pillContentColor.copy(alpha = 0.12f),
        contentColor = pillContentColor,
    ) {
        Text(
            text = deltaText,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            modifier =
                Modifier
                    .padding(
                        horizontal = MaterialTheme.spacing.small,
                        vertical = MaterialTheme.spacing.hairline,
                    ).testTag(DASHBOARD_DELTA_PILL_TAG),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Secondary-text treatment, keyed by card identity rather than by inspecting the text itself:
// only cards whose secondary text is an actual delta (a change since baseline/yesterday) get
// the pill; range/duration and averaged text (Sleep Duration, Circadian, Heart Rate) stay as
// plain bounded text.
internal fun CardId.usesDeltaPill(): Boolean =
    when (this) {
        CardId.SLEEP_SCORE,
        CardId.READINESS,
        CardId.HRV,
        CardId.SLEEP_RHR,
        CardId.RESTING_HR,
        CardId.STRAIN_RATIO,
        -> true
        else -> false
    }

@Composable
fun DashboardValueRenderer(
    presentation: DashboardMetricPresentation,
    contentColor: Color,
    cardId: CardId,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
        Text(
            text = presentation.valueText,
            style = MaterialTheme.typography.displaySmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.weight(1f))
        presentation.unitText.takeIf(String::isNotBlank)?.let { unit ->
            Text(
                text = unit,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }
        presentation.secondaryText?.let { secondary ->
            if (cardId.usesDeltaPill()) {
                DashboardMetricDeltaPill(secondary)
            } else {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
