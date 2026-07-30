package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import app.readylytics.health.core.designsystem.LocalStatusColors
import app.readylytics.health.core.designsystem.StatusColors
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.M3MetricGauge
import app.readylytics.health.core.ui.components.gaugeColor
import app.readylytics.health.domain.model.MetricStatus

internal const val DASHBOARD_METRIC_CARD_TAG = "dashboard_metric_card"
internal const val DASHBOARD_GAUGE_TAG = "dashboard_metric_gauge"
internal const val DASHBOARD_BAR_TAG = "dashboard_metric_bar"

internal fun DashboardMetricVisual.progressFraction(): Float? =
    when (this) {
        is DashboardMetricVisual.Score -> markerFraction
        is DashboardMetricVisual.Goal -> markerFraction
        is DashboardMetricVisual.PersonalBaseline -> markerFraction
        is DashboardMetricVisual.ReferenceRange -> markerFraction
        is DashboardMetricVisual.ValueOnly -> null
    }

fun metricStatusColor(
    status: MetricStatus,
    statusColors: StatusColors,
    surfaceVariant: Color,
): Color =
    when (status) {
        MetricStatus.OPTIMAL -> statusColors.optimal
        MetricStatus.NEUTRAL -> statusColors.neutral
        MetricStatus.WARNING -> statusColors.warning
        MetricStatus.POOR -> statusColors.poor
        MetricStatus.NO_DATA, MetricStatus.CALIBRATING -> surfaceVariant
    }

@Composable
fun metricStatusColor(
    status: MetricStatus,
    surfaceVariant: Color = MaterialTheme.colorScheme.surfaceVariant,
): Color = metricStatusColor(status, LocalStatusColors.current, surfaceVariant)

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
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = deltaText,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        modifier =
                            Modifier.padding(
                                horizontal = MaterialTheme.spacing.small,
                                vertical = MaterialTheme.spacing.hairline,
                            ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardBarRenderer(
    presentation: DashboardMetricPresentation,
    modifier: Modifier = Modifier,
) {
    val isUnavailable =
        presentation.visual.let {
            when (it) {
                is DashboardMetricVisual.Score -> it.unavailableReason != null
                is DashboardMetricVisual.Goal -> it.unavailableReason != null
                is DashboardMetricVisual.PersonalBaseline -> it.unavailableReason != null
                is DashboardMetricVisual.ReferenceRange -> it.unavailableReason != null
                is DashboardMetricVisual.ValueOnly -> false
            }
        }

    val bands =
        when (val v = presentation.visual) {
            is DashboardMetricVisual.Score -> v.bands
            is DashboardMetricVisual.Goal -> v.bands
            is DashboardMetricVisual.PersonalBaseline -> v.bands
            is DashboardMetricVisual.ReferenceRange -> v.bands
            is DashboardMetricVisual.ValueOnly -> emptyList()
        }

    val markerFraction =
        when (val v = presentation.visual) {
            is DashboardMetricVisual.Score -> v.markerFraction
            is DashboardMetricVisual.Goal -> v.markerFraction
            is DashboardMetricVisual.PersonalBaseline -> v.markerFraction
            is DashboardMetricVisual.ReferenceRange -> v.markerFraction
            is DashboardMetricVisual.ValueOnly -> null
        }

    val activeColor =
        if (isUnavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val trackColor =
        if (isUnavailable) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.outlineVariant

    val statusColors = LocalStatusColors.current
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val bandColorMap =
        bands.associateWith { band ->
            if (isUnavailable) trackColor else metricStatusColor(band.status, statusColors, surfaceVariantColor)
        }

    Box(
        modifier = modifier.fillMaxWidth().height(100.dp).testTag(DASHBOARD_BAR_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp)) {
            val strokeWidth = size.height
            val startY = size.height / 2

            // Base track
            drawLine(
                color = trackColor,
                start = Offset(0f, startY),
                end = Offset(size.width, startY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )

            // Bands
            bands.forEach { band ->
                val bandColor = bandColorMap[band] ?: trackColor
                drawLine(
                    color = bandColor,
                    start = Offset(size.width * band.startFraction, startY),
                    end = Offset(size.width * band.endFraction, startY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            // Progress (for Score/Goal)
            val shouldDrawProgress =
                presentation.visual is DashboardMetricVisual.Score || presentation.visual is DashboardMetricVisual.Goal
            if (shouldDrawProgress && markerFraction != null) {
                drawLine(
                    color = activeColor,
                    start = Offset(0f, startY),
                    end = Offset(size.width * markerFraction, startY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round,
                )
            }

            // Marker dot
            if (markerFraction != null) {
                drawCircle(
                    color = activeColor,
                    radius = strokeWidth / 2 + 4.dp.toPx(),
                    center = Offset(size.width * markerFraction, startY),
                )
            }
        }

        val textColor =
            if (isUnavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        Text(
            text = presentation.valueText,
            style = MaterialTheme.typography.headlineLarge,
            color = textColor,
        )
    }
}

@Composable
fun DashboardValueRenderer(
    presentation: DashboardMetricPresentation,
    contentColor: Color,
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
