package app.readylytics.health.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.util.roundToPercentInt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun M3ScoreDial(
    score: Float?,
    label: String,
    modifier: Modifier = Modifier,
    maxScore: Float = 100f,
    status: MetricStatus? = null,
    displayText: String? = null,
    unitLabel: String? = null,
    comparisonText: String? = null,
    comparisonTone: GaugeComparisonTone = GaugeComparisonTone.NEUTRAL,
    tooltipDescription: String? = null,
    onClick: () -> Unit = {},
) {
    val effectiveStatus =
        status ?: when {
            score == null -> MetricStatus.CALIBRATING
            label.contains("RAS", ignoreCase = true) -> {
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
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val animatedProgress by animateFloatAsState(
        targetValue = ((score ?: 0f) / maxScore).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "dial_progress_$label",
    )

    // Allow-listed: reusable dial widget. The arc uses the raw `score` (above); this is the
    // center-label fallback for a passed-in score. Callers showing a DailySummary metric should
    // pass `displayText = metrics.<field>Rounded.toString()` for canonical rounding.
    val scoreText = displayText ?: (score?.roundToPercentInt()?.toString() ?: "—")
    val semanticDesc = "$label: $scoreText"
    val scoreColor =
        if (score != null) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

    Card(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .height(156.dp)
                .semantics { contentDescription = semanticDesc },
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tooltipDescription != null) {
                    MetricTooltip(
                        description = tooltipDescription,
                        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .aspectRatio(1.35f),
                contentAlignment = Alignment.Center,
            ) {
                SoftArcGauge(
                    progress = animatedProgress,
                    progressColor = progressColor,
                    trackColor = trackColor,
                )
                GaugeCenterContent(
                    scoreText = scoreText,
                    unitLabel = unitLabel,
                    scoreColor = scoreColor,
                )
            }

            if (comparisonText != null) {
                GaugeComparisonChip(
                    text = comparisonText,
                    tone = comparisonTone,
                )
            } else {
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun GaugeCenterContent(
    scoreText: String,
    unitLabel: String?,
    scoreColor: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(
            targetState = scoreText,
            transitionSpec = {
                (fadeIn(animationSpec = tween(160)) togetherWith fadeOut(animationSpec = tween(120)))
                    .using(SizeTransform(clip = false))
            },
            label = "dial_value",
        ) { value ->
            Text(
                text = value,
                style =
                    if (value.length >= 5) {
                        MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 25.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        MaterialTheme.typography.displaySmall.copy(
                            fontSize = 36.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                color = scoreColor,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        if (!unitLabel.isNullOrBlank()) {
            Text(
                text = unitLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SoftArcGauge(
    progress: Float,
    progressColor: Color,
    trackColor: Color,
) {
    val isDarkTheme = isSystemInDarkTheme()
    Canvas(modifier = Modifier.size(width = 122.dp, height = 96.dp)) {
        val strokeWidth = 11.dp.toPx()
        val haloStrokeWidth = 24.dp.toPx()
        val halfStroke = strokeWidth / 2f
        val arcSize = Size(size.width - strokeWidth, (size.height * 1.45f) - strokeWidth)
        val arcTopLeft = Offset(halfStroke, halfStroke)
        val startAngle = 145f
        val sweepAngle = 250f
        val activeSweep = sweepAngle * progress.coerceIn(0f, 1f)

        if (activeSweep > 0f) {
            drawProgressiveHalo(
                progressColor = progressColor,
                startAngle = startAngle,
                activeSweep = activeSweep,
                topLeft = arcTopLeft,
                size = arcSize,
                strokeWidth = haloStrokeWidth,
                maxAlpha = if (isDarkTheme) 0.18f else 0.08f,
            )
        }

        drawArc(
            color = trackColor,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        if (activeSweep > 0f) {
            drawArc(
                color = progressColor,
                startAngle = startAngle,
                sweepAngle = activeSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            val endpoint = arcEndpoint(startAngle + activeSweep, arcTopLeft, arcSize)
            drawCircle(
                color = progressColor.copy(alpha = if (isDarkTheme) 0.20f else 0.10f),
                radius = 16.dp.toPx(),
                center = endpoint,
            )
            drawCircle(
                color = progressColor,
                radius = 6.dp.toPx(),
                center = endpoint,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawProgressiveHalo(
    progressColor: Color,
    startAngle: Float,
    activeSweep: Float,
    topLeft: Offset,
    size: Size,
    strokeWidth: Float,
    maxAlpha: Float,
) {
    val segmentCount = 18
    val segmentSweep = activeSweep / segmentCount
    repeat(segmentCount) { index ->
        val segmentStart = startAngle + segmentSweep * index
        val t = (index + 1f) / segmentCount
        drawArc(
            color = progressColor.copy(alpha = maxAlpha * t * t),
            startAngle = segmentStart,
            sweepAngle = segmentSweep,
            useCenter = false,
            topLeft = topLeft,
            size = size,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

private fun arcEndpoint(
    angleDegrees: Float,
    topLeft: Offset,
    size: Size,
): Offset {
    val angleRadians = angleDegrees * (PI.toFloat() / 180f)
    val center = Offset(topLeft.x + size.width / 2f, topLeft.y + size.height / 2f)
    return Offset(
        x = center.x + cos(angleRadians) * size.width / 2f,
        y = center.y + sin(angleRadians) * size.height / 2f,
    )
}
