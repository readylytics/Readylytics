package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.util.roundToPercentInt

@Composable
fun M3ScoreDial(
    score: Float?,
    label: String,
    modifier: Modifier = Modifier,
    maxScore: Float = 100f,
    status: MetricStatus? = null,
    displayText: String? = null,
    tooltipDescription: String? = null,
    onClick: () -> Unit = {},
) {
    val effectiveStatus =
        status ?: when {
            score == null -> MetricStatus.CALIBRATING
            label.contains("PAI", ignoreCase = true) -> {
                when {
                    score >= 100f -> MetricStatus.OPTIMAL
                    score >= 75f -> MetricStatus.NEUTRAL
                    score >= 50f -> MetricStatus.WARNING
                    else -> MetricStatus.POOR
                }
            }
            score >= 85f -> MetricStatus.OPTIMAL
            score >= 60f -> MetricStatus.NEUTRAL
            score >= 40f -> MetricStatus.WARNING
            else -> MetricStatus.POOR
        }

    val progressColor = effectiveStatus.gaugeColor()
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    val animatedProgress by animateFloatAsState(
        targetValue = ((score ?: 0f) / maxScore).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "dial_progress_$label",
    )

    val scoreText = displayText ?: (score?.roundToPercentInt()?.toString() ?: "—")
    val semanticDesc = "$label: $scoreText"

    Box(
        modifier =
            modifier
                .size(130.dp)
                .semantics { contentDescription = semanticDesc }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        DialArc(
            progress = animatedProgress,
            progressColor = progressColor,
            trackColor = trackColor,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val textStyle = if (scoreText.length >= 5) {
                MaterialTheme.typography.headlineMedium
            } else {
                MaterialTheme.typography.displaySmall
            }
            Text(
                text = scoreText,
                style = textStyle,
                color = if (score != null) progressColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tooltipDescription != null) {
            MetricTooltip(
                description = tooltipDescription,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun DialArc(
    progress: Float,
    progressColor: Color,
    trackColor: Color,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 14.dp.toPx()
        val halfStroke = strokeWidth / 2f
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val arcTopLeft = Offset(halfStroke, halfStroke)

        drawArc(
            color = trackColor,
            startAngle = 135f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        if (progress > 0f) {
            drawArc(
                color = progressColor,
                startAngle = 135f,
                sweepAngle = 270f * progress,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}
