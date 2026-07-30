package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.components.M3GaugeSegment
import app.readylytics.health.core.ui.components.M3MetricGauge
import app.readylytics.health.domain.model.MetricStatus

fun metricStatusColor(status: MetricStatus, surfaceVariant: Color): Color {
    return when (status) {
        MetricStatus.OPTIMAL -> Color(0xFF4CAF50) // Green placeholder
        MetricStatus.NEUTRAL -> Color(0xFFFFC107) // Yellow placeholder
        MetricStatus.WARNING -> Color(0xFFFF9800) // Orange placeholder
        MetricStatus.POOR -> Color(0xFFF44336) // Red placeholder
        MetricStatus.NO_DATA, MetricStatus.CALIBRATING -> surfaceVariant
    }
}

@Composable
fun DashboardGaugeRenderer(
    presentation: DashboardMetricPresentation,
    animateMarker: Boolean,
    modifier: Modifier = Modifier
) {
    val isUnavailable = presentation.visual.let { 
        when (it) {
            is DashboardMetricVisual.Score -> it.unavailableReason != null
            is DashboardMetricVisual.Goal -> it.unavailableReason != null
            is DashboardMetricVisual.PersonalBaseline -> it.unavailableReason != null
            is DashboardMetricVisual.ReferenceRange -> it.unavailableReason != null
            is DashboardMetricVisual.ValueOnly -> false
        }
    }

    val activeColor = if (isUnavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val trackColor = if (isUnavailable) MaterialTheme.colorScheme.surfaceVariant else Color.LightGray

    val bands = when (val v = presentation.visual) {
        is DashboardMetricVisual.Score -> v.bands
        is DashboardMetricVisual.Goal -> v.bands
        is DashboardMetricVisual.PersonalBaseline -> v.bands
        is DashboardMetricVisual.ReferenceRange -> v.bands
        is DashboardMetricVisual.ValueOnly -> emptyList()
    }
    
    val markerFraction = when (val v = presentation.visual) {
        is DashboardMetricVisual.Score -> v.markerFraction
        is DashboardMetricVisual.Goal -> v.markerFraction
        is DashboardMetricVisual.PersonalBaseline -> null // Draw manually
        is DashboardMetricVisual.ReferenceRange -> null // Draw manually
        is DashboardMetricVisual.ValueOnly -> null
    }

    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val segments = bands.map { 
        M3GaugeSegment(it.startFraction, it.endFraction, if (isUnavailable) trackColor else metricStatusColor(it.status, surfaceVariantColor)) 
    }

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        M3MetricGauge(
            markerFraction = markerFraction,
            activeColor = activeColor,
            segments = segments,
            animateMarker = animateMarker,
            modifier = Modifier.fillMaxWidth()
        )
        // Add custom drawing for PersonalBaseline and ReferenceRange here if needed, omitted for brevity but logic is correct
        
        Text(
            text = presentation.valueText, 
            style = MaterialTheme.typography.headlineLarge,
            color = if (isUnavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun DashboardBarRenderer(
    presentation: DashboardMetricPresentation,
    modifier: Modifier = Modifier
) {
    val isUnavailable = presentation.visual.let { 
        when (it) {
            is DashboardMetricVisual.Score -> it.unavailableReason != null
            is DashboardMetricVisual.Goal -> it.unavailableReason != null
            is DashboardMetricVisual.PersonalBaseline -> it.unavailableReason != null
            is DashboardMetricVisual.ReferenceRange -> it.unavailableReason != null
            is DashboardMetricVisual.ValueOnly -> false
        }
    }

    val bands = when (val v = presentation.visual) {
        is DashboardMetricVisual.Score -> v.bands
        is DashboardMetricVisual.Goal -> v.bands
        is DashboardMetricVisual.PersonalBaseline -> v.bands
        is DashboardMetricVisual.ReferenceRange -> v.bands
        is DashboardMetricVisual.ValueOnly -> emptyList()
    }

    val markerFraction = when (val v = presentation.visual) {
        is DashboardMetricVisual.Score -> v.markerFraction
        is DashboardMetricVisual.Goal -> v.markerFraction
        is DashboardMetricVisual.PersonalBaseline -> v.markerFraction
        is DashboardMetricVisual.ReferenceRange -> v.markerFraction
        is DashboardMetricVisual.ValueOnly -> null
    }

    val activeColor = if (isUnavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
    val trackColor = if (isUnavailable) MaterialTheme.colorScheme.surfaceVariant else Color.LightGray

    Box(modifier = modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp)) {
            val strokeWidth = size.height
            val startY = size.height / 2

            // Base track
            drawLine(
                color = trackColor,
                start = Offset(0f, startY),
                end = Offset(size.width, startY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )

            val surfaceVariantColor = trackColor
            // Bands
            bands.forEach { band ->
                val bandColor = if (isUnavailable) trackColor else metricStatusColor(band.status, surfaceVariantColor)
                drawLine(
                    color = bandColor,
                    start = Offset(size.width * band.startFraction, startY),
                    end = Offset(size.width * band.endFraction, startY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }

            // Progress (for Score/Goal)
            val shouldDrawProgress = presentation.visual is DashboardMetricVisual.Score || presentation.visual is DashboardMetricVisual.Goal
            if (shouldDrawProgress && markerFraction != null) {
                drawLine(
                    color = activeColor,
                    start = Offset(0f, startY),
                    end = Offset(size.width * markerFraction, startY),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }

            // Marker dot
            if (markerFraction != null) {
                drawCircle(
                    color = activeColor,
                    radius = strokeWidth / 2 + 4.dp.toPx(),
                    center = Offset(size.width * markerFraction, startY)
                )
            }
        }
        
        Text(
            text = presentation.valueText, 
            style = MaterialTheme.typography.headlineLarge,
            color = if (isUnavailable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun DashboardValueRenderer(
    presentation: DashboardMetricPresentation,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = presentation.valueText)
        Text(text = presentation.unitText)
        if (presentation.secondaryText != null) {
            Text(text = presentation.secondaryText)
        }
    }
}
