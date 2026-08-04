package app.readylytics.health.core.ui.components.metriccard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.M3MetricGaugeWithValue
import app.readylytics.health.core.ui.components.containerColor
import app.readylytics.health.core.ui.components.gaugeColor
import app.readylytics.health.core.ui.components.metricVisualizationTrackColor
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual

const val UNIVERSAL_METRIC_CARD_TAG = "dashboard_metric_card"
const val UNIVERSAL_GAUGE_TAG = "dashboard_metric_gauge"
const val UNIVERSAL_BAR_TAG = "dashboard_metric_bar"
const val UNIVERSAL_DELTA_PILL_TAG = "dashboard_metric_delta_pill"
const val UNIVERSAL_TITLE_INFO_ICON_TAG = "dashboard_metric_title_info_icon"

fun UniversalMetricVisual.progressFraction(): Float? =
    when (this) {
        is UniversalMetricVisual.Score -> markerFraction
        is UniversalMetricVisual.Goal -> markerFraction
        is UniversalMetricVisual.PersonalBaseline -> markerFraction
        is UniversalMetricVisual.ReferenceRange -> markerFraction
        is UniversalMetricVisual.ValueOnly -> null
    }

// Fixed slot for the delta pill / plain secondary text, kept out of the weighted value row so it
// cannot be squeezed away at large font scales.
private val UNIVERSAL_SECONDARY_SLOT_HEIGHT = 20.dp

@Composable
fun UniversalGaugeRenderer(
    presentation: UniversalMetricPresentation,
    secondaryUsesPill: Boolean,
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
        modifier = modifier.fillMaxSize().testTag(UNIVERSAL_GAUGE_TAG),
        verticalArrangement = Arrangement.Bottom,
    ) {
        M3MetricGaugeWithValue(
            markerFraction = markerFraction,
            activeColor = activeColor,
            markerColor = presentation.status.containerColor(),
            valueText = presentation.gaugeValueText,
            unitText = presentation.gaugeUnitText,
            valueColor = activeColor,
            unitColor = contentColor.copy(alpha = 0.8f),
            animateMarker = animateMarker,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.hairline))

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(UNIVERSAL_SECONDARY_SLOT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            presentation.secondaryText?.takeIf(String::isNotBlank)?.let { deltaText ->
                if (secondaryUsesPill) {
                    UniversalMetricDeltaPill(deltaText)
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

@Composable
fun UniversalBarRenderer(
    presentation: UniversalMetricPresentation,
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

    UniversalValueUnitColumn(
        presentation = presentation,
        contentColor = contentColor,
        secondaryUsesPill = secondaryUsesPill,
        modifier = modifier,
    ) {
        val progress = progressFraction?.coerceIn(0f, 1f) ?: 0f
        LinearProgressIndicator(
            progress = { progress },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(MaterialTheme.dimens.metricTrackThickness)
                    .padding(horizontal = MaterialTheme.spacing.extraSmall)
                    .testTag(UNIVERSAL_BAR_TAG),
            color = activeColor,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
        )
    }
}

// Shared vertical structure for Bar and Value mode: a baseline-aligned value/unit row on top, the
// track slot in the middle, and the secondary/delta slot at the bottom. Bar mode draws its Canvas
// into the track slot, Value mode leaves the same slot empty, so the two modes differ only in
// whether the track is painted and everything else stays put when switching between them.
@Composable
private fun UniversalValueUnitColumn(
    presentation: UniversalMetricPresentation,
    contentColor: Color,
    secondaryUsesPill: Boolean,
    modifier: Modifier = Modifier,
    track: @Composable ColumnScope.() -> Unit,
) {
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
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Baseline (not bounding-box bottom) alignment so the small unit label sits on
                // the same baseline as the much larger value, the way "56 bpm" is normally set.
                modifier = Modifier.alignByBaseline(),
            )
            if (presentation.unitText.isNotBlank()) {
                Text(
                    text = presentation.unitText,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        // Tight gaps around the track: with the larger displaySmall value and the thicker track,
        // the column's fixed elements still have to fit the value line's full height at font
        // scale 1.0, and the weighted value row above is the single element that yields further.
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))

        track()

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))

        Box(
            modifier = Modifier.fillMaxWidth().height(UNIVERSAL_SECONDARY_SLOT_HEIGHT),
            contentAlignment = Alignment.CenterStart,
        ) {
            presentation.secondaryText?.takeIf(String::isNotBlank)?.let { deltaText ->
                if (secondaryUsesPill) {
                    UniversalMetricDeltaPill(deltaText)
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
private fun UniversalMetricDeltaPill(deltaText: String) {
    val pillContentColor = LocalContentColor.current
    Surface(
        shape = CircleShape,
        color = pillContentColor.copy(alpha = 0.12f),
        contentColor = pillContentColor,
    ) {
        Text(
            text = deltaText,
            style = MaterialTheme.typography.labelSmall,
            modifier =
                Modifier
                    .padding(
                        horizontal = MaterialTheme.spacing.small,
                        vertical = MaterialTheme.spacing.hairline,
                    ).testTag(UNIVERSAL_DELTA_PILL_TAG),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun UniversalValueRenderer(
    presentation: UniversalMetricPresentation,
    contentColor: Color,
    secondaryUsesPill: Boolean,
    modifier: Modifier = Modifier,
) {
    UniversalValueUnitColumn(
        presentation = presentation,
        contentColor = contentColor,
        secondaryUsesPill = secondaryUsesPill,
        modifier = modifier,
    ) {
        // Value mode is Bar mode without the painted track: the slot is still reserved so the
        // value row and the secondary/delta row keep their position when the mode is switched.
        Spacer(modifier = Modifier.fillMaxWidth().height(MaterialTheme.dimens.metricTrackThickness))
    }
}
