package app.readylytics.health.feature.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.M3MetricGauge
import app.readylytics.health.core.ui.components.M3GaugeSegment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import app.readylytics.health.domain.model.MetricStatus

@Composable
fun DashboardGaugeRenderer(
    presentation: DashboardMetricPresentation,
    animateMarker: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val markerFraction = when (val visual = presentation.visual) {
            is DashboardMetricVisual.Score -> visual.markerFraction
            is DashboardMetricVisual.Goal -> visual.markerFraction
            is DashboardMetricVisual.PersonalBaseline -> visual.markerFraction
            is DashboardMetricVisual.ReferenceRange -> visual.markerFraction
            is DashboardMetricVisual.ValueOnly -> null
        }
        
        M3MetricGauge(
            markerFraction = markerFraction,
            activeColor = MaterialTheme.colorScheme.primary, // Using primary for now
            segments = emptyList(), // Simplify for now
            animateMarker = animateMarker,
            modifier = Modifier.fillMaxSize()
        )
        Text(text = presentation.valueText) // Retain real text
    }
}

@Composable
fun DashboardBarRenderer(
    presentation: DashboardMetricPresentation,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Minimal bar representation
        Text(text = "Bar")
        Text(text = presentation.valueText)
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
