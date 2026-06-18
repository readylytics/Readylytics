package app.readylytics.health.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.domain.model.MetricStatus
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun M3ScoreGaugeCard(
    title: String,
    score: Float?,
    displayText: String,
    unitText: String,
    modifier: Modifier = Modifier,
    maxScore: Float = 100f,
    status: MetricStatus? = null,
    deltaText: String? = null,
    tooltipDescription: String? = null,
    onClick: () -> Unit = {},
) {
    val effectiveStatus =
        status ?: when {
            score == null -> MetricStatus.CALIBRATING
            title.contains("RAS", ignoreCase = true) -> {
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
        label = "gauge_progress_$title",
    )

    val semanticDesc =
        if (deltaText != null) {
            "$title: $displayText $unitText, $deltaText"
        } else {
            "$title: $displayText $unitText"
        }

    Card(
        onClick = onClick,
        modifier =
            modifier
                .height(156.dp)
                .semantics {
                    contentDescription = semanticDesc
                    role = Role.Button
                },
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Header Row: Title and Tooltip
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { heading() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
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

            Spacer(modifier = Modifier.height(10.dp))

            // Center Area: Gauge and Value
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Soft Arc Gauge (shifted up slightly to leave more space below)
                Canvas(
                    modifier =
                        Modifier
                            .width(120.dp)
                            .height(60.dp)
                            .padding(bottom = 6.dp),
                ) {
                    val strokeWidthPx = 6.dp.toPx()
                    val dotRadiusPx = 4.dp.toPx()

                    // Add padding to prevent any clipping of rounded caps or the endpoint dot
                    val horizontalPadding = strokeWidthPx / 2f + dotRadiusPx
                    val verticalPadding = strokeWidthPx / 2f + dotRadiusPx

                    val arcWidth = size.width - 2 * horizontalPadding
                    val radius = arcWidth / 2f
                    val centerX = size.width / 2f
                    val centerY = size.height - verticalPadding

                    val topLeft = Offset(centerX - radius, centerY - radius)
                    val arcSize = Size(radius * 2, radius * 2)

                    // Draw track
                    drawArc(
                        color = trackColor,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                    )

                    // Draw active arc progress
                    if (animatedProgress > 0f) {
                        drawArc(
                            color = progressColor,
                            startAngle = 180f,
                            sweepAngle = 180f * animatedProgress,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round),
                        )

                        // Draw endpoint dot
                        val endAngle = 180f + (180f * animatedProgress)
                        val endAngleRad = Math.toRadians(endAngle.toDouble())
                        val dotX = centerX + radius * cos(endAngleRad).toFloat()
                        val dotY = centerY + radius * sin(endAngleRad).toFloat()

                        drawCircle(
                            color = progressColor,
                            radius = dotRadiusPx,
                            center = Offset(dotX, dotY),
                        )
                    }
                }

                // Centered Value & Unit
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val textStyle =
                        if (displayText.length >= 6) {
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
                        text = displayText,
                        style = textStyle,
                        color = if (score != null) progressColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = if (!unitText.isNullOrEmpty()) unitText else " ",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color =
                            if (!unitText.isNullOrEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Footer: Baseline Chip
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!deltaText.isNullOrEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) {
                        Text(
                            text = deltaText,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}
