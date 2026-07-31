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
import androidx.compose.ui.text.font.FontWeight
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
            modifier = Modifier.fillMaxWidth(),
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
                val textStyle =
                    if (presentation.valueText.length >= 6) {
                        MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                        )
                    } else {
                        MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                        )
                    }
                Text(
                    text = presentation.valueText,
                    style = textStyle,
                    color = activeColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Text(
                    text = presentation.unitText.ifBlank { " " },
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color =
                        if (presentation.unitText.isNotBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
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
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
        ) {
            val valueTextStyle =
                if (presentation.valueText.length >= 6) {
                    MaterialTheme.typography.titleLarge.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.3).sp,
                    )
                } else {
                    MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    )
                }
            Text(
                text = presentation.valueText,
                style = valueTextStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (presentation.unitText.isNotBlank()) {
                Text(
                    text = presentation.unitText,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

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
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardMetricDeltaPill(deltaText: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

// Value-mode secondary-text treatment, keyed by card identity rather than by inspecting the
// text itself: only cards whose secondary text is an actual delta (a change since baseline/
// yesterday) get the pill; range/duration and averaged text stay as plain bounded text.
internal enum class DashboardValueLayout {
    STANDARD,
    RANGE_OR_DURATION,
    DELTA,
}

internal fun CardId.valueLayout(): DashboardValueLayout =
    when (this) {
        CardId.SLEEP_DURATION,
        CardId.CIRCADIAN_CONSISTENCY,
        CardId.HEART_RATE,
        -> DashboardValueLayout.RANGE_OR_DURATION
        CardId.SLEEP_SCORE,
        CardId.READINESS,
        CardId.HRV,
        CardId.SLEEP_RHR,
        CardId.RESTING_HR,
        CardId.STRAIN_RATIO,
        -> DashboardValueLayout.DELTA
        else -> DashboardValueLayout.STANDARD
    }

@Composable
fun DashboardValueRenderer(
    presentation: DashboardMetricPresentation,
    contentColor: Color,
    cardId: CardId,
    modifier: Modifier = Modifier,
) {
    val layout = cardId.valueLayout()

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
            if (layout == DashboardValueLayout.DELTA) {
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
