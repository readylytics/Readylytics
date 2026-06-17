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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.ui.dashboard.BaselineDeltaDirection

@Composable
fun SoftArcMetricCard(
    title: String,
    value: String,
    unit: String,
    status: MetricStatus,
    tooltip: String,
    progress: Float,
    modifier: Modifier = Modifier,
    baselineDeltaText: String? = null,
    baselineDeltaDirection: BaselineDeltaDirection? = null,
    onClick: (() -> Unit)? = null,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gaugeColor = status.gaugeColor()
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "soft_arc_metric_progress_$title",
    )
    val cardModifier =
        modifier
            .height(156.dp)
            .semantics {
                contentDescription = listOfNotNull(title, value, unit, baselineDeltaText).joinToString(", ")
                if (onClick != null) role = Role.Button
            }
    val cardColors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContentColor = contentColor,
        )

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = cardModifier,
            shape = MaterialTheme.shapes.large,
            colors = cardColors,
        ) {
            SoftArcMetricCardContent(
                title = title,
                value = value,
                unit = unit,
                tooltip = tooltip,
                animatedProgress = animatedProgress,
                gaugeColor = gaugeColor,
                contentColor = contentColor,
                secondaryColor = secondaryColor,
                baselineDeltaText = baselineDeltaText,
                baselineDeltaDirection = baselineDeltaDirection,
            )
        }
    } else {
        Card(
            modifier = cardModifier,
            shape = MaterialTheme.shapes.large,
            colors = cardColors,
        ) {
            SoftArcMetricCardContent(
                title = title,
                value = value,
                unit = unit,
                tooltip = tooltip,
                animatedProgress = animatedProgress,
                gaugeColor = gaugeColor,
                contentColor = contentColor,
                secondaryColor = secondaryColor,
                baselineDeltaText = baselineDeltaText,
                baselineDeltaDirection = baselineDeltaDirection,
            )
        }
    }
}

@Composable
private fun SoftArcMetricCardContent(
    title: String,
    value: String,
    unit: String,
    tooltip: String,
    animatedProgress: Float,
    gaugeColor: Color,
    contentColor: Color,
    secondaryColor: Color,
    baselineDeltaText: String?,
    baselineDeltaDirection: BaselineDeltaDirection?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
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
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetricTooltip(description = tooltip, iconTint = secondaryColor)
        }

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            SoftArc(
                progress = animatedProgress,
                progressColor = gaugeColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(width = 132.dp, height = 74.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value,
                    style =
                        if (value.length >= 5) {
                            MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp)
                        } else {
                            MaterialTheme.typography.displaySmall.copy(fontSize = 34.sp)
                        },
                    color = contentColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                if (unit.isNotBlank()) {
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelMedium,
                        color = secondaryColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }

        if (baselineDeltaText != null) {
            BaselineDeltaChip(
                text = baselineDeltaText,
                direction = baselineDeltaDirection,
                tint = gaugeColor,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SoftArc(
    progress: Float,
    progressColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 10.dp.toPx()
        val halfStroke = strokeWidth / 2f
        val arcSize = Size(size.width - strokeWidth, (size.height * 1.5f) - strokeWidth)
        val arcTopLeft = Offset(halfStroke, halfStroke)
        val startAngle = 200f
        val sweepAngle = 140f

        drawArc(
            color = trackColor,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        if (progress > 0f) {
            drawArc(
                color = progressColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle * progress,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun BaselineDeltaChip(
    text: String,
    direction: BaselineDeltaDirection?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier.semantics {
                contentDescription =
                    when (direction) {
                        BaselineDeltaDirection.UP -> "$text, above baseline"
                        BaselineDeltaDirection.DOWN -> "$text, below baseline"
                        BaselineDeltaDirection.EQUAL -> "$text, at baseline"
                        null -> text
                    }
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = tint,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
