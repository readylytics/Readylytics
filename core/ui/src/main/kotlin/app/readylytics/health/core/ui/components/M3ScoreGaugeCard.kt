package app.readylytics.health.core.ui.components

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.domain.model.MetricStatus

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
    onClick: (() -> Unit)? = null,
) {
    val isClickable = onClick != null
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

    val markerFraction = ((score ?: 0f) / maxScore).coerceIn(0f, 1f)

    val semanticDesc =
        if (deltaText != null) {
            "$title: $displayText $unitText, $deltaText"
        } else {
            "$title: $displayText $unitText"
        }

    val baseModifier = modifier.height(MaterialTheme.dimens.cardHeight)
    val semanticsModifier =
        if (isClickable) {
            baseModifier.semantics {
                contentDescription = semanticDesc
                role = Role.Button
            }
        } else {
            baseModifier.semantics {
                contentDescription = semanticDesc
            }
        }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = semanticsModifier,
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        ) {
            GaugeCardContent(
                title = title,
                displayText = displayText,
                unitText = unitText,
                markerFraction = markerFraction,
                progressColor = progressColor,
                deltaText = deltaText,
                tooltipDescription = tooltipDescription,
                score = score,
            )
        }
    } else {
        Card(
            modifier = semanticsModifier,
            shape = MaterialTheme.shapes.large,
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
        ) {
            GaugeCardContent(
                title = title,
                displayText = displayText,
                unitText = unitText,
                markerFraction = markerFraction,
                progressColor = progressColor,
                deltaText = deltaText,
                tooltipDescription = tooltipDescription,
                score = score,
            )
        }
    }
}

@Composable
private fun GaugeCardContent(
    title: String,
    displayText: String,
    unitText: String,
    markerFraction: Float,
    progressColor: androidx.compose.ui.graphics.Color,
    deltaText: String?,
    tooltipDescription: String?,
    score: Float?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal = MaterialTheme.spacing.medium,
                    vertical = MaterialTheme.spacing.smallMedium,
                ),
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
                style = MaterialTheme.typography.titleMedium,
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
        M3MetricGaugeWithValue(
            markerFraction = markerFraction,
            activeColor = progressColor,
            valueText = displayText,
            unitText = unitText,
            valueColor = if (score != null) progressColor else MaterialTheme.colorScheme.onSurfaceVariant,
            unitColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            animateMarker = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(MaterialTheme.spacing.hairline))

        // Footer: Baseline Chip
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (!deltaText.isNullOrEmpty()) {
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
